package desertreet.blockchat.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import desertreet.blockchat.BlockChatClientMod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes an MP4 at the recorded resolution via FFmpeg and uploads frames to a
 * {@link DynamicTexture}. Call {@link #pump()} from {@code Screen.render} each
 * frame so playback can run at display refresh rate (not limited to 20Hz tick).
 */
public final class VideoPreviewPlayer {

	private static final int QUEUE_CAPACITY = 8;
	private static final int VIDEO_PREROLL_FRAMES = 3;
	private static final long AUDIO_ATTACH_GRACE_NS = TimeUnit.MILLISECONDS.toNanos(750L);
	private static final AudioFormat PREVIEW_AUDIO_FORMAT = new AudioFormat(48_000f, 16, 2, true, false);
	private static final int AUDIO_BUFFER_BYTES = 16_384;
	private static int textureCounter;

	private record DecodedFrame(long index, byte[] rgbaData) {
	}

	private final Path videoPath;
	private final int decodeWidth;
	private final int decodeHeight;
	private final int frameByteSize;
	private volatile List<Path> externalAudioTracks;
	private String ffmpegPath;

	private Process process;
	private Process audioProcess;
	private Thread readerThread;
	private Thread audioReaderThread;
	private final ArrayBlockingQueue<DecodedFrame> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private volatile boolean running;
	private volatile boolean readerDone;
	private volatile boolean audioRunning;
	private volatile boolean audioReaderDone;
	private volatile boolean audioEnabled;

	private DynamicTexture texture;
	private Identifier textureId;
	private boolean hasFrame;
	private long playbackClockStartNs;
	private double playbackClockBaseSeconds;
	private long lastPresentedFrameIndex = -1L;
	private boolean startBarrierActive;
	private boolean audioStartPending;
	private long audioAttachDeadlineNs;
	private double pendingAudioStartSeconds;
	private boolean usingAudioClock;
	private double audioClockBaseSeconds;
	private SourceDataLine audioLine;

	public VideoPreviewPlayer(
		Path videoPath,
		int recordWidth,
		int recordHeight,
		List<Path> externalAudioTracks,
		boolean audioEnabled
	) {
		this.videoPath = videoPath;
		this.decodeWidth = recordWidth;
		this.decodeHeight = recordHeight;
		this.externalAudioTracks = externalAudioTracks != null ? List.copyOf(externalAudioTracks) : List.of();
		this.audioEnabled = audioEnabled;
		long bytes = (long) recordWidth * (long) recordHeight * 4L;
		this.frameByteSize = bytes > Integer.MAX_VALUE - 16 ? -1 : (int) bytes;
	}

	public void start() {
		if (decodeWidth <= 0 || decodeHeight <= 0 || frameByteSize <= 0) {
			BlockChatClientMod.LOGGER.warn(
				"Invalid or too large video preview size: {}x{}",
				decodeWidth, decodeHeight
			);
			return;
		}
		String ffmpeg = FFmpegLocator.getPath();
		if (ffmpeg == null) {
			BlockChatClientMod.LOGGER.warn("FFmpeg not available for video preview");
			return;
		}
		ffmpegPath = ffmpeg;

		texture = new DynamicTexture(
			() -> "blockchat_video_preview", decodeWidth, decodeHeight, false
		);
		textureId = Identifier.fromNamespaceAndPath(
			"desertreet", "blockchat_vidpreview_" + (textureCounter++)
		);
		Minecraft.getInstance().getTextureManager().register(textureId, texture);

		startProcesses(ffmpeg);
	}

	public void setAudioEnabled(boolean enabled) {
		if (audioEnabled == enabled) {
			return;
		}
		audioEnabled = enabled;
		if (!enabled) {
			stopAudioProcess();
			return;
		}
		startAudioAtCurrentPosition();
	}

	public void setExternalAudioTracks(List<Path> tracks) {
		List<Path> nextTracks = tracks != null ? List.copyOf(tracks) : List.of();
		if (externalAudioTracks.equals(nextTracks)) {
			return;
		}
		externalAudioTracks = nextTracks;
		if (audioEnabled) {
			startAudioAtCurrentPosition();
		}
	}

	private void startProcesses(String ffmpeg) {
		startProcesses(ffmpeg, false);
	}

	private void startProcesses(String ffmpeg, boolean preserveCurrentFrame) {
		reanchorPlaybackClock(0.0);
		lastPresentedFrameIndex = -1L;
		if (!preserveCurrentFrame) {
			hasFrame = false;
		}
		startBarrierActive = true;
		audioStartPending = false;
		audioAttachDeadlineNs = 0L;
		pendingAudioStartSeconds = 0.0;
		usingAudioClock = false;
		audioClockBaseSeconds = 0.0;
		startVideoDecodeProcess(ffmpeg);
	}

	private void startVideoDecodeProcess(String ffmpeg) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
				ffmpeg,
				"-i", videoPath.toString(),
				"-vsync", "0",
				"-f", "rawvideo",
				"-pix_fmt", "rgba",
				"-s", decodeWidth + "x" + decodeHeight,
				"-v", "error",
				"pipe:1"
			);
			pb.redirectErrorStream(false);
			process = pb.start();
			running = true;
			readerDone = false;

			readerThread = new Thread(this::readLoop, "BlockChat-VideoPreview-Reader");
			readerThread.setDaemon(true);
			readerThread.start();

			new Thread(() -> drainStderr(process), "BlockChat-VideoPreview-Stderr").start();
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to start video preview decode", e);
			running = false;
		}
	}

	private void startAudioDecodeProcess(String ffmpeg, double startSeconds) {
		try {
			ProcessBuilder pb = new ProcessBuilder(buildAudioDecodeCommand(ffmpeg, startSeconds));
			pb.redirectErrorStream(false);
			audioProcess = pb.start();
			audioRunning = true;
			audioReaderDone = false;

			audioReaderThread = new Thread(this::readAudioLoop, "BlockChat-VideoPreview-Audio");
			audioReaderThread.setDaemon(true);
			audioReaderThread.start();

			new Thread(() -> drainStderr(audioProcess), "BlockChat-VideoPreview-AudioStderr").start();
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to start video preview audio decode", e);
			audioRunning = false;
		}
	}

	private List<String> buildAudioDecodeCommand(String ffmpeg, double startSeconds) {
		java.util.ArrayList<String> command = new java.util.ArrayList<>();
		command.add(ffmpeg);
		command.add("-hide_banner");
		if (externalAudioTracks.isEmpty()) {
			addSeek(command, startSeconds);
			command.add("-i");
			command.add(videoPath.toString());
			command.add("-vn");
		} else {
			for (Path audioTrack : externalAudioTracks) {
				addSeek(command, startSeconds);
				command.add("-i");
				command.add(audioTrack.toString());
			}
			if (externalAudioTracks.size() > 1) {
				command.add("-filter_complex");
				command.add(buildExternalTrackMixFilter(externalAudioTracks.size()));
				command.add("-map");
				command.add("[blockchat_preview_audio]");
			} else {
				command.add("-map");
				command.add("0:a:0");
			}
		}
		command.add("-f");
		command.add("s16le");
		command.add("-acodec");
		command.add("pcm_s16le");
		command.add("-ar");
		command.add("48000");
		command.add("-ac");
		command.add("2");
		command.add("-v");
		command.add("error");
		command.add("pipe:1");
		return command;
	}

	private void addSeek(List<String> command, double startSeconds) {
		if (startSeconds <= 0.0) {
			return;
		}
		command.add("-ss");
		command.add(String.format(Locale.ROOT, "%.3f", startSeconds));
	}

	private String buildExternalTrackMixFilter(int audioTrackCount) {
		StringBuilder filter = new StringBuilder();
		for (int i = 0; i < audioTrackCount; i++) {
			filter.append("[").append(i).append(":a]");
		}
		filter.append("amix=inputs=").append(audioTrackCount)
			.append(":normalize=0:dropout_transition=0[blockchat_preview_audio]");
		return filter.toString();
	}

	/**
	 * Advance playback; call once per GUI render frame (not per game tick).
	 */
	public void pump() {
		if (texture == null) {
			return;
		}

		if (!running && readerDone && frameQueue.isEmpty()) {
			restartForLoop();
			return;
		}

		if (!ensurePlaybackStarted()) {
			return;
		}
		maybePromoteAudioClock();

		long targetFrameIndex = Math.max(
			0L,
			(long) Math.floor(getPlaybackPositionSeconds() * BlockChatVideoConstants.CAPTURE_FPS)
		);
		DecodedFrame newestFrame = null;
		while (true) {
			DecodedFrame frame = frameQueue.peek();
			if (frame == null) {
				break;
			}
			if (frame.index() > targetFrameIndex) {
				if (newestFrame == null && lastPresentedFrameIndex < 0L) {
					newestFrame = frameQueue.poll();
				}
				break;
			}
			newestFrame = frameQueue.poll();
		}
		if (newestFrame != null) {
			uploadFrame(newestFrame.rgbaData());
			hasFrame = true;
			lastPresentedFrameIndex = newestFrame.index();
		}
	}

	public Identifier getTextureId() {
		return textureId;
	}

	public int getFrameWidth() {
		return decodeWidth;
	}

	public int getFrameHeight() {
		return decodeHeight;
	}

	public boolean hasFrame() {
		return hasFrame;
	}

	public void stop() {
		running = false;
		audioRunning = false;
		if (process != null) {
			process.destroyForcibly();
			process = null;
		}
		if (audioProcess != null) {
			audioProcess.destroyForcibly();
			audioProcess = null;
		}
		if (readerThread != null) {
			try {
				readerThread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			readerThread = null;
		}
		if (audioReaderThread != null) {
			try {
				audioReaderThread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			audioReaderThread = null;
		}
		closeAudioLine();
		if (textureId != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			textureId = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
		playbackClockStartNs = 0L;
		playbackClockBaseSeconds = 0.0;
		lastPresentedFrameIndex = -1L;
		startBarrierActive = false;
		audioStartPending = false;
		audioAttachDeadlineNs = 0L;
		pendingAudioStartSeconds = 0.0;
		usingAudioClock = false;
		audioClockBaseSeconds = 0.0;
	}

	private void restartForLoop() {
		String ffmpeg = ffmpegPath != null ? ffmpegPath : FFmpegLocator.getPath();
		if (ffmpeg == null) return;

		stopVideoProcess();
		stopAudioProcess();

		frameQueue.clear();
		playbackClockStartNs = 0L;
		playbackClockBaseSeconds = 0.0;
		lastPresentedFrameIndex = -1L;
		startBarrierActive = false;
		audioStartPending = false;
		audioAttachDeadlineNs = 0L;
		pendingAudioStartSeconds = 0.0;
		usingAudioClock = false;
		audioClockBaseSeconds = 0.0;
		readerDone = false;
		audioReaderDone = false;
		startProcesses(ffmpeg, true);
	}

	private void startAudioAtCurrentPosition() {
		if (ffmpegPath == null) {
			ffmpegPath = FFmpegLocator.getPath();
		}
		if (ffmpegPath == null) {
			return;
		}
		double currentPositionSeconds = getPlaybackPositionSeconds();
		reanchorPlaybackClock(currentPositionSeconds);
		usingAudioClock = false;
		audioClockBaseSeconds = currentPositionSeconds;
		pendingAudioStartSeconds = currentPositionSeconds;
		audioAttachDeadlineNs = System.nanoTime() + AUDIO_ATTACH_GRACE_NS;
		audioStartPending = true;
		stopAudioProcess();
		startAudioDecodeProcess(ffmpegPath, currentPositionSeconds);
	}

	private double getPlaybackPositionSeconds() {
		if (usingAudioClock && audioLine != null) {
			return Math.max(0.0, audioClockBaseSeconds + getAudioLinePositionSeconds());
		}
		return getWallClockPositionSeconds();
	}

	private double getWallClockPositionSeconds() {
		if (playbackClockStartNs == 0L) {
			return playbackClockBaseSeconds;
		}
		double elapsedSeconds = (System.nanoTime() - playbackClockStartNs) / 1_000_000_000.0;
		return Math.max(0.0, playbackClockBaseSeconds + elapsedSeconds);
	}

	private double getAudioLinePositionSeconds() {
		if (audioLine == null) {
			return 0.0;
		}
		return Math.max(0.0, audioLine.getMicrosecondPosition() / 1_000_000.0);
	}

	private void reanchorPlaybackClock(double positionSeconds) {
		playbackClockBaseSeconds = Math.max(0.0, positionSeconds);
		playbackClockStartNs = System.nanoTime();
	}

	private boolean ensurePlaybackStarted() {
		if (!startBarrierActive) {
			return true;
		}
		if (frameQueue.size() < VIDEO_PREROLL_FRAMES) {
			return false;
		}
		if (!audioEnabled) {
			startBarrierActive = false;
			reanchorPlaybackClock(0.0);
			return true;
		}
		if (!audioStartPending) {
			if (ffmpegPath == null) {
				ffmpegPath = FFmpegLocator.getPath();
			}
			if (ffmpegPath == null) {
				startBarrierActive = false;
				reanchorPlaybackClock(0.0);
				return true;
			}
			pendingAudioStartSeconds = 0.0;
			audioStartPending = true;
			audioAttachDeadlineNs = System.nanoTime() + AUDIO_ATTACH_GRACE_NS;
			startAudioDecodeProcess(ffmpegPath, pendingAudioStartSeconds);
			return false;
		}
		if (audioLine != null) {
			audioClockBaseSeconds = -getAudioLinePositionSeconds();
			usingAudioClock = true;
			startBarrierActive = false;
			reanchorPlaybackClock(0.0);
			return true;
		}
		if (System.nanoTime() < audioAttachDeadlineNs) {
			return false;
		}
		startBarrierActive = false;
		reanchorPlaybackClock(0.0);
		return true;
	}

	private void maybePromoteAudioClock() {
		if (!audioEnabled || usingAudioClock || audioLine == null) {
			return;
		}
		audioClockBaseSeconds = Math.max(
			0.0,
			getWallClockPositionSeconds() - getAudioLinePositionSeconds()
		);
		usingAudioClock = true;
	}

	private void uploadFrame(byte[] rgbaData) {
		if (texture == null) return;
		NativeImage pixels = texture.getPixels();
		if (pixels == null) return;

		int idx = 0;
		for (int y = 0; y < decodeHeight; y++) {
			for (int x = 0; x < decodeWidth; x++) {
				int r = rgbaData[idx] & 0xFF;
				int g = rgbaData[idx + 1] & 0xFF;
				int b = rgbaData[idx + 2] & 0xFF;
				int a = rgbaData[idx + 3] & 0xFF;
				pixels.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
				idx += 4;
			}
		}
		texture.upload();
	}

	private void readLoop() {
		try {
			InputStream stdout = process.getInputStream();
			byte[] buf = new byte[frameByteSize];
			long decodedFrameIndex = 0L;
			while (running) {
				int totalRead = 0;
				while (totalRead < frameByteSize) {
					int n = stdout.read(buf, totalRead, frameByteSize - totalRead);
					if (n < 0) {
						running = false;
						readerDone = true;
						return;
					}
					totalRead += n;
				}
				byte[] frame = new byte[frameByteSize];
				System.arraycopy(buf, 0, frame, 0, frameByteSize);
				DecodedFrame decodedFrame = new DecodedFrame(decodedFrameIndex++, frame);
				if (!frameQueue.offer(decodedFrame, 50, TimeUnit.MILLISECONDS)) {
					frameQueue.poll();
					frameQueue.offer(decodedFrame);
				}
			}
		} catch (Exception e) {
			if (running) {
				BlockChatClientMod.LOGGER.warn("Video preview reader error", e);
			}
		} finally {
			readerDone = true;
			running = false;
		}
	}

	private void readAudioLoop() {
		SourceDataLine line = null;
		try {
			line = openAudioLine();
			if (line == null || audioProcess == null) {
				audioRunning = false;
				return;
			}
			InputStream stdout = audioProcess.getInputStream();
			byte[] buffer = new byte[AUDIO_BUFFER_BYTES];
			while (audioRunning) {
				int read = stdout.read(buffer);
				if (read < 0) {
					break;
				}
				if (read > 0) {
					line.write(buffer, 0, read);
				}
			}
			line.drain();
		} catch (Exception e) {
			if (audioRunning) {
				BlockChatClientMod.LOGGER.warn("Video preview audio reader error", e);
			}
		} finally {
			audioReaderDone = true;
			audioRunning = false;
			if (line != null) {
				line.stop();
				line.flush();
				line.close();
				if (audioLine == line) {
					audioLine = null;
				}
			}
		}
	}

	private SourceDataLine openAudioLine() throws LineUnavailableException {
		SourceDataLine line = AudioSystem.getSourceDataLine(PREVIEW_AUDIO_FORMAT);
		line.open(PREVIEW_AUDIO_FORMAT);
		line.start();
		audioLine = line;
		return line;
	}

	private void stopVideoProcess() {
		running = false;
		Process oldProc = process;
		process = null;
		if (oldProc != null) {
			oldProc.destroyForcibly();
		}
		Thread oldReader = readerThread;
		readerThread = null;
		if (oldReader != null) {
			try {
				oldReader.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void stopAudioProcess() {
		audioRunning = false;
		Process oldProc = audioProcess;
		audioProcess = null;
		if (oldProc != null) {
			oldProc.destroyForcibly();
		}
		Thread oldReader = audioReaderThread;
		audioReaderThread = null;
		if (oldReader != null) {
			try {
				oldReader.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		closeAudioLine();
	}

	private void closeAudioLine() {
		if (audioLine == null) {
			return;
		}
		audioLine.stop();
		audioLine.flush();
		audioLine.close();
		audioLine = null;
		usingAudioClock = false;
	}

	private void drainStderr(Process targetProcess) {
		if (targetProcess == null) return;
		try (InputStream err = targetProcess.getErrorStream()) {
			byte[] discard = new byte[1024];
			while (err.read(discard) >= 0) { /* drain */ }
		} catch (Exception ignored) {
		}
	}
}
