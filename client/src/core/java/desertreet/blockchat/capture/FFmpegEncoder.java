package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatLogPrivacy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages an FFmpeg subprocess that encodes raw RGBA frames piped to stdin
 * into an H.264 work file. A dedicated writer thread drains a bounded
 * frame queue so the render thread never blocks on I/O.
 */
public final class FFmpegEncoder {

	private static final int QUEUE_CAPACITY = 10;
	private static final QueuedFrame POISON = new QueuedFrame(null, 0);

	private Process process;
	private Thread writerThread;
	private Thread stderrThread;
	private ArrayBlockingQueue<QueuedFrame> frameQueue;
	private volatile boolean running;
	private Path outputPath;

	public void start(int width, int height, Path outputPath) throws IOException {
		this.outputPath = outputPath;
		this.frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

		String ffmpeg = FFmpegLocator.getPath();
		if (ffmpeg == null) {
			throw new IOException("FFmpeg not available");
		}

		ProcessBuilder pb = new ProcessBuilder(
			ffmpeg,
			"-y",
			"-f", "rawvideo",
			"-pix_fmt", "rgba",
			"-s", width + "x" + height,
			"-r", String.valueOf(BlockChatVideoConstants.CAPTURE_FPS),
			"-i", "pipe:0",
			"-vf", "vflip",
			"-c:v", "libx264",
			"-preset", "ultrafast",
			"-tune", "zerolatency",
			"-pix_fmt", "yuv420p",
			outputPath.toString()
		);
		pb.redirectErrorStream(false);

		process = pb.start();
		running = true;
		CaptureDiagnostics.logEvent(
			"encoder_started",
			"size=" + width + "x" + height + " output=" + BlockChatLogPrivacy.privacySafePath(outputPath)
		);

		writerThread = new Thread(this::writerLoop, "BlockChat-FFmpeg-Writer");
		writerThread.setDaemon(true);
		writerThread.start();

		stderrThread = new Thread(this::stderrDrain, "BlockChat-FFmpeg-Stderr");
		stderrThread.setDaemon(true);
		stderrThread.start();

		BlockChatClientMod.LOGGER.info("FFmpegEncoder started: {}x{} -> {}", width, height, outputPath);
	}

	/**
	 * Enqueue a frame for encoding. Non-blocking -- drops the frame
	 * if the queue is full (gameplay takes priority over recording).
	 */
	public void submitFrame(byte[] rgbaData) {
		submitFrame(rgbaData, 1);
	}

	public void submitFrame(byte[] rgbaData, int repeatCount) {
		if (!running || rgbaData == null) {
			return;
		}
		int safeRepeatCount = Math.max(1, repeatCount);
		QueuedFrame queuedFrame = new QueuedFrame(rgbaData, safeRepeatCount);
		if (!frameQueue.offer(queuedFrame)) {
			for (int i = 0; i < safeRepeatCount; i++) {
				CaptureDiagnostics.recordEncoderDropped(frameQueue.size());
			}
			BlockChatClientMod.LOGGER.trace("Frame dropped (queue full)");
			return;
		}
		for (int i = 0; i < safeRepeatCount; i++) {
			CaptureDiagnostics.recordEncoderSubmitted(frameQueue.size());
		}
	}

	public void submitFrameBlocking(byte[] rgbaData, int repeatCount, long timeoutMs) throws InterruptedException {
		if (!running || rgbaData == null) {
			return;
		}
		int safeRepeatCount = Math.max(1, repeatCount);
		QueuedFrame queuedFrame = new QueuedFrame(rgbaData, safeRepeatCount);
		boolean enqueued = frameQueue.offer(queuedFrame, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
		if (!enqueued) {
			for (int i = 0; i < safeRepeatCount; i++) {
				CaptureDiagnostics.recordEncoderDropped(frameQueue.size());
			}
			BlockChatClientMod.LOGGER.warn("Timed out submitting BlockChat frame(s) to FFmpeg");
			return;
		}
		for (int i = 0; i < safeRepeatCount; i++) {
			CaptureDiagnostics.recordEncoderSubmitted(frameQueue.size());
		}
	}

	public void stop() {
		if (!running) {
			return;
		}
		running = false;

		frameQueue.offer(POISON);

		try {
			if (writerThread != null) {
				writerThread.join(5000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		try {
			if (process != null) {
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					CaptureDiagnostics.logEvent("encoder_process_timeout", "output=" + BlockChatLogPrivacy.privacySafePath(outputPath));
					BlockChatClientMod.LOGGER.warn("FFmpeg process forcibly destroyed");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (process != null) {
				process.destroyForcibly();
			}
		}

		try {
			if (stderrThread != null) {
				stderrThread.join(2000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (process != null) {
			try {
				CaptureDiagnostics.logEvent(
					"encoder_stopped",
					"exit_code=" + process.exitValue() + " output=" + BlockChatLogPrivacy.privacySafePath(outputPath)
				);
			} catch (IllegalThreadStateException ignored) {
				CaptureDiagnostics.logEvent("encoder_stopped", "exit_code=running output=" + BlockChatLogPrivacy.privacySafePath(outputPath));
			}
		}

		BlockChatClientMod.LOGGER.info("FFmpegEncoder stopped: {}", outputPath);
	}

	public boolean isRunning() {
		return running;
	}

	public Path getOutputPath() {
		return outputPath;
	}

	private void writerLoop() {
		try {
			OutputStream stdin = process.getOutputStream();
			while (true) {
				QueuedFrame queuedFrame = frameQueue.poll(100, TimeUnit.MILLISECONDS);
				if (queuedFrame == POISON) {
					break;
				}
				if (queuedFrame == null) {
					if (!running) {
						break;
					}
					continue;
				}
				for (int i = 0; i < queuedFrame.repeatCount(); i++) {
					stdin.write(queuedFrame.rgbaData());
					CaptureDiagnostics.recordEncoderWritten();
				}
			}
			stdin.flush();
			stdin.close();
		} catch (IOException e) {
			if (running) {
				BlockChatClientMod.LOGGER.error("FFmpeg writer I/O error", e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void stderrDrain() {
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getErrorStream())
		)) {
			String line;
			while ((line = reader.readLine()) != null) {
				BlockChatClientMod.LOGGER.trace("ffmpeg: {}", line);
			}
		} catch (IOException ignored) {
		}
	}

	private record QueuedFrame(byte[] rgbaData, int repeatCount) {
	}
}
