package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatLogPrivacy;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight single-session diagnostics for BlockChat recording/export issues.
 * Writes one rolling log under {@code gameDir/blockchat/last-recording-debug.log} when enabled so bad clips can be
 * compared against wall-clock time and exported media metadata.
 */
public final class CaptureDiagnostics {

	private static final String LOG_FILE_NAME = "last-recording-debug.log";

	private static volatile boolean enabled = false;
	private static volatile Session currentSession;

	private CaptureDiagnostics() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean newEnabled) {
		if (enabled == newEnabled) {
			return;
		}
		enabled = newEnabled;
		if (!newEnabled) {
			closeCurrentSession("disabled");
		}
	}

	public static void startSession(
		Path blockChatDir,
		String clipId,
		int width,
		int height,
		Path workingDirectory,
		Path rawVideoPath,
		Path finalOutputPath
	) {
		if (!enabled) {
			closeCurrentSession("disabled");
			return;
		}

		closeCurrentSession("replaced");

		if (blockChatDir == null) {
			return;
		}

		try {
			BlockChatPaths.ensureBlockChatDirectory(blockChatDir);
			Path logPath = blockChatDir.resolve(LOG_FILE_NAME);
			BufferedWriter writer = Files.newBufferedWriter(
				logPath,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);
			Session session = new Session(logPath, writer);
			currentSession = session;
			session.writeLine("blockchat_capture_diagnostics_v1");
			session.writeLine("started_at=" + Instant.now());
			session.writeLine("clip_id=" + safeValue(clipId));
			session.writeLine("capture_fps=" + BlockChatVideoConstants.CAPTURE_FPS);
			session.writeLine("capture_size=" + width + "x" + height);
			session.writeLine("working_directory=" + safePath(workingDirectory));
			session.writeLine("raw_video_path=" + safePath(rawVideoPath));
			session.writeLine("final_output_path=" + safePath(finalOutputPath));
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("Failed to start BlockChat capture diagnostics", e);
			currentSession = null;
		}
	}

	public static void logEvent(String event, String details) {
		if (!enabled) {
			return;
		}
		Session session = currentSession;
		if (session == null) {
			return;
		}
		session.writeEvent(event, details);
	}

	public static void recordFrameRenderTick() {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.frameRenderCount.increment();
			updateGap(session.lastFrameRenderNs, session.maxFrameRenderGapNs);
		});
	}

	public static void recordGrabRateLimited() {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> session.grabRateLimitedCount.increment());
	}

	public static void recordGrabQueued() {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.grabQueuedCount.increment();
			updateGap(session.lastGrabQueuedNs, session.maxGrabQueuedGapNs);
		});
	}

	public static void recordFrameReady() {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.frameReadyCount.increment();
			updateGap(session.lastFrameReadyNs, session.maxFrameReadyGapNs);
		});
	}

	public static void recordEncoderSubmitted(int queueDepth) {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.encoderSubmittedCount.increment();
			updateGap(session.lastEncoderSubmittedNs, session.maxEncoderSubmittedGapNs);
			session.maxEncoderQueueDepth.accumulateAndGet(queueDepth, Math::max);
		});
	}

	public static void recordEncoderDropped(int queueDepth) {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.encoderDroppedCount.increment();
			session.maxEncoderQueueDepth.accumulateAndGet(queueDepth, Math::max);
		});
	}

	public static void recordEncoderWritten() {
		if (!enabled) {
			return;
		}
		recordTimingEvent(session -> {
			session.encoderWrittenCount.increment();
			updateGap(session.lastEncoderWrittenNs, session.maxEncoderWrittenGapNs);
		});
	}

	public static void logStopSummary(long elapsedMs) {
		if (!enabled) {
			return;
		}
		Session session = currentSession;
		if (session == null) {
			return;
		}
		long submitted = session.encoderSubmittedCount.sum();
		long written = session.encoderWrittenCount.sum();
		session.writeEvent(
			"stop_summary",
			"elapsed_ms=" + elapsedMs
				+ " rendered=" + session.frameRenderCount.sum()
				+ " rate_limited=" + session.grabRateLimitedCount.sum()
				+ " grab_queued=" + session.grabQueuedCount.sum()
				+ " frame_ready=" + session.frameReadyCount.sum()
				+ " submitted=" + submitted
				+ " dropped=" + session.encoderDroppedCount.sum()
				+ " written=" + written
				+ " expected_ms_from_submitted=" + formatMillis(framesToMillis(submitted))
				+ " expected_ms_from_written=" + formatMillis(framesToMillis(written))
				+ " speedup_vs_written=" + formatFactor(calculateSpeedup(elapsedMs, written))
				+ " max_queue_depth=" + session.maxEncoderQueueDepth.get()
		);
	}

	public static void logAudioTrackSummary(List<Path> audioTracks) {
		if (!enabled) {
			return;
		}
		Session session = currentSession;
		if (session == null) {
			return;
		}
		List<String> parts = new ArrayList<>();
		if (audioTracks != null) {
			for (Path audioTrack : audioTracks) {
				parts.add(audioTrack != null ? audioTrack.getFileName().toString() : "null");
			}
		}
		session.writeEvent(
			"audio_tracks",
			"count=" + (audioTracks != null ? audioTracks.size() : 0)
				+ " names=" + String.join(",", parts)
		);
	}

	public static void probeMedia(String label, Path mediaPath) {
		if (!enabled) {
			return;
		}
		Session session = currentSession;
		if (session == null) {
			return;
		}
		if (mediaPath == null || !Files.isRegularFile(mediaPath)) {
			session.writeEvent(
				"probe_skipped",
				"label=" + safeValue(label) + " reason=missing_file path=" + safePath(mediaPath)
			);
			return;
		}

		session.writeEvent(
			"probe_skipped",
			"label=" + safeValue(label) + " reason=probe_removed path=" + safePath(mediaPath)
		);
	}

	public static void finishSession(String outcome) {
		if (!enabled) {
			return;
		}
		closeCurrentSession(outcome);
	}

	private static void closeCurrentSession(String outcome) {
		Session session = currentSession;
		if (session == null) {
			return;
		}
		synchronized (CaptureDiagnostics.class) {
			if (currentSession != session) {
				return;
			}
			session.writeEvent(
				"final_summary",
				"outcome=" + safeValue(outcome)
					+ " rendered=" + session.frameRenderCount.sum()
					+ " rate_limited=" + session.grabRateLimitedCount.sum()
					+ " grab_queued=" + session.grabQueuedCount.sum()
					+ " frame_ready=" + session.frameReadyCount.sum()
					+ " submitted=" + session.encoderSubmittedCount.sum()
					+ " dropped=" + session.encoderDroppedCount.sum()
					+ " written=" + session.encoderWrittenCount.sum()
					+ " max_queue_depth=" + session.maxEncoderQueueDepth.get()
					+ " max_render_gap_ms=" + formatMillis(nanosToMillis(session.maxFrameRenderGapNs.get()))
					+ " max_grab_gap_ms=" + formatMillis(nanosToMillis(session.maxGrabQueuedGapNs.get()))
					+ " max_ready_gap_ms=" + formatMillis(nanosToMillis(session.maxFrameReadyGapNs.get()))
					+ " max_submit_gap_ms=" + formatMillis(nanosToMillis(session.maxEncoderSubmittedGapNs.get()))
					+ " max_written_gap_ms=" + formatMillis(nanosToMillis(session.maxEncoderWrittenGapNs.get()))
			);
			session.close();
			currentSession = null;
		}
	}

	private static void recordTimingEvent(java.util.function.Consumer<Session> operation) {
		if (!enabled) {
			return;
		}
		Session session = currentSession;
		if (session == null) {
			return;
		}
		operation.accept(session);
	}

	private static void updateGap(AtomicLong lastTimestampNs, AtomicLong maxGapNs) {
		long now = System.nanoTime();
		long previous = lastTimestampNs.getAndSet(now);
		if (previous == 0L) {
			return;
		}
		long gap = now - previous;
		maxGapNs.accumulateAndGet(gap, Math::max);
	}

	private static double framesToMillis(long frames) {
		return (frames * 1000.0D) / BlockChatVideoConstants.CAPTURE_FPS;
	}

	private static double calculateSpeedup(long elapsedMs, long framesWritten) {
		if (elapsedMs <= 0L || framesWritten <= 0L) {
			return 0.0D;
		}
		double expectedVideoMs = framesToMillis(framesWritten);
		if (expectedVideoMs <= 0.0D) {
			return 0.0D;
		}
		return elapsedMs / expectedVideoMs;
	}

	private static double nanosToMillis(long nanos) {
		return nanos / 1_000_000.0D;
	}

	private static String formatMillis(double millis) {
		return String.format(Locale.ROOT, "%.3f", millis);
	}

	private static String formatFactor(double factor) {
		return String.format(Locale.ROOT, "%.4f", factor);
	}

	private static String safePath(Path path) {
		return BlockChatLogPrivacy.privacySafePath(path);
	}

	private static String safeValue(String value) {
		if (value == null) {
			return "null";
		}
		return value.replace('\n', ' ').replace('\r', ' ');
	}

	private static final class Session {

		private final Path logPath;
		private final BufferedWriter writer;
		private final long startedAtNs = System.nanoTime();

		private final LongAdder frameRenderCount = new LongAdder();
		private final LongAdder grabRateLimitedCount = new LongAdder();
		private final LongAdder grabQueuedCount = new LongAdder();
		private final LongAdder frameReadyCount = new LongAdder();
		private final LongAdder encoderSubmittedCount = new LongAdder();
		private final LongAdder encoderDroppedCount = new LongAdder();
		private final LongAdder encoderWrittenCount = new LongAdder();
		private final AtomicInteger maxEncoderQueueDepth = new AtomicInteger();

		private final AtomicLong lastFrameRenderNs = new AtomicLong();
		private final AtomicLong maxFrameRenderGapNs = new AtomicLong();
		private final AtomicLong lastGrabQueuedNs = new AtomicLong();
		private final AtomicLong maxGrabQueuedGapNs = new AtomicLong();
		private final AtomicLong lastFrameReadyNs = new AtomicLong();
		private final AtomicLong maxFrameReadyGapNs = new AtomicLong();
		private final AtomicLong lastEncoderSubmittedNs = new AtomicLong();
		private final AtomicLong maxEncoderSubmittedGapNs = new AtomicLong();
		private final AtomicLong lastEncoderWrittenNs = new AtomicLong();
		private final AtomicLong maxEncoderWrittenGapNs = new AtomicLong();

		private Session(Path logPath, BufferedWriter writer) {
			this.logPath = logPath;
			this.writer = writer;
		}

		private synchronized void writeEvent(String event, String details) {
			writeLine(
				"t_ms=" + formatMillis(nanosToMillis(System.nanoTime() - startedAtNs))
					+ " event=" + safeValue(event)
					+ (details != null && !details.isBlank() ? " " + details : "")
			);
		}

		private synchronized void writeLine(String line) {
			try {
				writer.write(line);
				writer.newLine();
				writer.flush();
			} catch (IOException e) {
				BlockChatClientMod.LOGGER.debug("Failed to write BlockChat capture diagnostics to {}", logPath, e);
			}
		}

		private synchronized void close() {
			try {
				writer.close();
			} catch (IOException e) {
				BlockChatClientMod.LOGGER.debug("Failed to close BlockChat capture diagnostics {}", logPath, e);
			}
		}
	}
}
