package desertreet.blockchat.capture;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.capture.macos.MacOsCaptureArtifacts;
import desertreet.blockchat.capture.macos.MacOsCaptureHelperLocator;
import desertreet.blockchat.capture.macos.MacOsCaptureHelperSession;
import desertreet.blockchat.capture.windows.WindowsNativeCaptureArtifacts;
import desertreet.blockchat.capture.windows.WindowsNativeCaptureHelperLocator;
import desertreet.blockchat.capture.windows.WindowsNativeCaptureHelperSession;
import desertreet.blockchat.compat.BlockChatPlayerMessages;
import desertreet.blockchat.compat.BlockChatRenderSystemCompat;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.hud.BlockChatHud;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatLogPrivacy;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * State machine that coordinates FrameGrabber and FFmpegEncoder
 * for video recording triggered by the J key.
 */
public final class VideoRecorder {

	private static final long MAX_DURATION_MS = 30_000;
	private static final int MACOS_MAX_CAPTURE_HEIGHT = 1080;
	private static final int WINDOWS_MAX_CAPTURE_HEIGHT = 1080;
	private static final DateTimeFormatter CLIP_TIME_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	public enum State { IDLE, RECORDING, STOPPING }

	private static State state = State.IDLE;
	private static FrameGrabber frameGrabber;
	private static FFmpegEncoder encoder;
	private static long startTimeMs;
	private static int capturedWidth;
	private static int capturedHeight;
	private static Path finalOutputPath;
	private static Path workingDirectory;
	private static CompletableFuture<AudioCaptureStart> audioCaptureStartFuture;
	private static long recordingStartNanos;
	private static long nextEncodedFrameIndex;
	private static byte[] lastCapturedFrame;

	private VideoRecorder() {
	}

	public static void toggle(Minecraft client) {
		switch (state) {
			case IDLE -> startRecording(client);
			case RECORDING -> stopRecording(client);
			case STOPPING -> { }
		}
	}

	public static boolean isRecording() {
		return state == State.RECORDING;
	}

	public static long getElapsedMs() {
		if (state != State.RECORDING) {
			return 0;
		}
		return System.currentTimeMillis() - startTimeMs;
	}

	/**
	 * Called from the render thread after the full frame is rendered.
	 * Captures a frame if recording is active.
	 */
	public static void onFrameRendered() {
		if (state != State.RECORDING) {
			return;
		}
		CaptureDiagnostics.recordFrameRenderTick();

		if (System.currentTimeMillis() - startTimeMs >= MAX_DURATION_MS) {
			Minecraft client = Minecraft.getInstance();
			stopRecording(client);
			return;
		}

		if (frameGrabber == null || encoder == null) {
			return;
		}

		Window window = Minecraft.getInstance().getWindow();
		int fbWidth = window.getWidth();
		int fbHeight = window.getHeight();
		if (fbWidth != capturedWidth || fbHeight != capturedHeight) {
			BlockChatClientMod.LOGGER.warn("Window resized during recording, stopping");
			stopRecording(Minecraft.getInstance());
			return;
		}

		frameGrabber.grabFrame((frame, captureTimeNanos) -> {
			if (frame != null && encoder != null && encoder.isRunning()) {
				submitCapturedFrame(frame, captureTimeNanos);
			}
		});
	}

	private static void startRecording(Minecraft client) {
		boolean windowsPlatform = isWindows();
		boolean useMacOsCaptureHelperVideoCapture = MacOsCaptureHelperLocator.isSupportedPlatform();
		boolean useWindowsNativeHelperVideoCapture = windowsPlatform
			&& (WindowsNativeCaptureHelperLocator.isHelperBuilt() || WindowsNativeCaptureHelperLocator.hasBuildScript());
		boolean useNativeHelperVideoCapture = useMacOsCaptureHelperVideoCapture || useWindowsNativeHelperVideoCapture;
		if (windowsPlatform && !useWindowsNativeHelperVideoCapture) {
			if (client.player != null) {
				BlockChatPlayerMessages.show(
					client.player,
					Component.literal(BlockChatStrings.prefixedMessage(BlockChatStrings.WINDOWS_HELPER_UNAVAILABLE_BUILD)),
					false
				);
			}
			return;
		}
		if (!useNativeHelperVideoCapture && !FFmpegLocator.isAvailable()) {
			if (client.player != null) {
				BlockChatPlayerMessages.show(
					client.player,
					Component.literal(BlockChatStrings.prefixedMessage(BlockChatStrings.FFMPEG_NOT_FOUND_INSTALL)),
					false
				);
			}
			return;
		}

		Window window = client.getWindow();
		if (useMacOsCaptureHelperVideoCapture) {
			int[] macSize = computeCappedCaptureSize(window.getWidth(), window.getHeight(), MACOS_MAX_CAPTURE_HEIGHT);
			capturedWidth = macSize[0];
			capturedHeight = macSize[1];
		} else if (useWindowsNativeHelperVideoCapture) {
			int[] windowsSize = computeCappedCaptureSize(
				window.getWidth(),
				window.getHeight(),
				WINDOWS_MAX_CAPTURE_HEIGHT
			);
			capturedWidth = windowsSize[0];
			capturedHeight = windowsSize[1];
		} else {
			capturedWidth = window.getWidth();
			capturedHeight = window.getHeight();
		}

		Path clipsDir = BlockChatPaths.getRuntimePath("clips");
		Path workDirRoot = BlockChatPaths.getRuntimePath("capture-work");
		try {
			BlockChatPaths.ensureRuntimeDirectory(BlockChatPaths.getRuntimeRoot());
			BlockChatPaths.ensureRuntimeDirectory(clipsDir);
			BlockChatPaths.ensureRuntimeDirectory(workDirRoot);
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.error("Failed to create clips directory", e);
			return;
		}

		String timestamp = LocalDateTime.now().format(CLIP_TIME_FMT);
		String clipId = "clip_" + timestamp;
		finalOutputPath = clipsDir.resolve("clip_" + timestamp + ".mp4");
		workingDirectory = workDirRoot.resolve(clipId);
		try {
			BlockChatPaths.ensureRuntimeDirectory(workingDirectory);
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.error("Failed to create BlockChat capture working directory", e);
			finalOutputPath = null;
			workingDirectory = null;
			return;
		}

		Path videoWorkPath = useMacOsCaptureHelperVideoCapture
			? workingDirectory.resolve("macos-video-silent.mp4")
			: useWindowsNativeHelperVideoCapture
				? finalOutputPath
				: workingDirectory.resolve("video.mkv");
		CaptureDiagnostics.startSession(
			BlockChatPaths.getClientLogDirectory(),
			clipId,
			capturedWidth,
			capturedHeight,
			workingDirectory,
			videoWorkPath,
			finalOutputPath
		);
		CaptureDiagnostics.logEvent("recording_start_requested", "clip_id=" + clipId);

		if (!useNativeHelperVideoCapture) {
			frameGrabber = new FrameGrabber();
			long frameGrabberInitStartedAt = System.nanoTime();
			frameGrabber.init(capturedWidth, capturedHeight);
			CaptureDiagnostics.logEvent(
				"frame_grabber_initialized",
				"duration_ms=" + formatDurationMs(System.nanoTime() - frameGrabberInitStartedAt)
			);

			encoder = new FFmpegEncoder();
			try {
				long encoderStartStartedAt = System.nanoTime();
				encoder.start(capturedWidth, capturedHeight, videoWorkPath);
				CaptureDiagnostics.logEvent(
					"encoder_start_completed",
					"duration_ms=" + formatDurationMs(System.nanoTime() - encoderStartStartedAt)
				);
			} catch (IOException e) {
				BlockChatClientMod.LOGGER.error("Failed to start FFmpeg encoder", e);
				CaptureDiagnostics.logEvent("encoder_start_failed", "message=" + e.getMessage());
				frameGrabber.cleanup();
				frameGrabber = null;
				encoder = null;
				finalOutputPath = null;
				workingDirectory = null;
				CaptureDiagnostics.finishSession("encoder_start_failed");
				return;
			}
		} else {
			frameGrabber = null;
			encoder = null;
			CaptureDiagnostics.logEvent(
				useMacOsCaptureHelperVideoCapture
					? "macos_helper_video_capture_enabled"
					: "windows_helper_video_capture_enabled",
				"capture_size=" + capturedWidth + "x" + capturedHeight
					+ (useMacOsCaptureHelperVideoCapture ? " max_height=" + MACOS_MAX_CAPTURE_HEIGHT : "")
			);
		}

		startTimeMs = System.currentTimeMillis();
		recordingStartNanos = System.nanoTime();
		nextEncodedFrameIndex = 0L;
		lastCapturedFrame = null;
		state = State.RECORDING;
		audioCaptureStartFuture = startPlatformAudioCaptureAsync(
			client,
			workingDirectory,
			finalOutputPath,
			useNativeHelperVideoCapture
		);
		CaptureDiagnostics.logEvent(
			"recording_started",
			"start_time_ms=" + startTimeMs + " audio_start_async=" + (audioCaptureStartFuture != null)
		);

		BlockChatClientMod.LOGGER.info("Recording started: {}x{}", capturedWidth, capturedHeight);
	}

	private static void stopRecording(Minecraft client) {
		if (state != State.RECORDING) {
			return;
		}
		state = State.STOPPING;

		FFmpegEncoder capturedEncoder = encoder;
		Path rawVideoPath = capturedEncoder != null ? capturedEncoder.getOutputPath() : null;
		Path targetClipPath = finalOutputPath != null ? finalOutputPath : rawVideoPath;
		Path captureWorkingDirectory = workingDirectory;
		long elapsed = System.currentTimeMillis() - startTimeMs;
		long elapsedNanos = System.nanoTime() - recordingStartNanos;
		byte[] lastFrameForPadding = lastCapturedFrame;
		long nextFrameIndexForPadding = nextEncodedFrameIndex;
		CaptureDiagnostics.logStopSummary(elapsed);
		CaptureDiagnostics.logEvent("stop_requested", "elapsed_ms=" + elapsed);

		BlockChatRenderSystemCompat.drainPendingTasks();

		if (frameGrabber != null) {
			frameGrabber.cleanup();
			frameGrabber = null;
		}

		encoder = null;

		CompletableFuture<AudioCaptureStart> capturedAudioCaptureStartFuture = audioCaptureStartFuture;
		audioCaptureStartFuture = null;
		finalOutputPath = null;
		workingDirectory = null;
		recordingStartNanos = 0L;
		nextEncodedFrameIndex = 0L;
		lastCapturedFrame = null;

		boolean helperOnlyCapture = capturedEncoder == null
			&& (MacOsCaptureHelperLocator.isSupportedPlatform()
				|| (isWindows() && (WindowsNativeCaptureHelperLocator.isHelperBuilt()
					|| WindowsNativeCaptureHelperLocator.hasBuildScript())));
		AudioCaptureStart immediateAudioCaptureStart = getImmediateAudioCaptureStart(capturedAudioCaptureStartFuture);
		boolean knownHelperStartFailure = helperOnlyCapture
			&& immediateAudioCaptureStart != null
			&& !immediateAudioCaptureStart.hasSession();
		boolean clipProcessing = capturedEncoder != null || isAudioCaptureProcessing(capturedAudioCaptureStartFuture);
		if (!knownHelperStartFailure) {
			// Publish the draft slot immediately so the composer can open now and own the Loading... state.
			publishPendingClipForComposer(
				targetClipPath,
				captureWorkingDirectory
			);
		}
		if (knownHelperStartFailure) {
			notifyCaptureUnavailable(
				client,
				isWindows()
					? BlockChatStrings.RECORDING_FAILED_WINDOWS_NEVER_STARTED
					: BlockChatStrings.RECORDING_FAILED_MACOS_NEVER_STARTED
			);
		} else {
			showStoppedRecordingToastOnMainThread(elapsed, capturedEncoder != null);
		}

		finalizeRecordingAsync(
			client,
			capturedEncoder,
			rawVideoPath,
			targetClipPath,
			captureWorkingDirectory,
			capturedAudioCaptureStartFuture,
			elapsed,
			elapsedNanos,
			lastFrameForPadding,
			nextFrameIndexForPadding
		);
	}

	private static void publishPendingClipForComposer(Path targetClipPath, Path captureWorkingDirectory) {
		PendingCapture.setPendingClip(
			targetClipPath,
			capturedWidth,
			capturedHeight,
			captureWorkingDirectory,
			List.of()
		);
	}

	private static void finalizeRecordingAsync(
		Minecraft client,
		FFmpegEncoder capturedEncoder,
		Path rawVideoPath,
		Path targetClipPath,
		Path captureWorkingDirectory,
		CompletableFuture<AudioCaptureStart> capturedAudioCaptureStartFuture,
		long elapsed,
		long elapsedNanos,
		byte[] lastFrameForPadding,
		long nextFrameIndexForPadding
	) {
		CompletableFuture.runAsync(() -> {
			boolean audioFailed = false;
			boolean audioTracksAvailable = false;
			Path effectiveRawVideoPath = rawVideoPath;
			Path recoveredDirectVideoPath = null;
			try {
				if (capturedEncoder != null) {
					padVideoTimeline(capturedEncoder, lastFrameForPadding, nextFrameIndexForPadding, elapsedNanos);
					long encoderStopStartedAt = System.nanoTime();
					capturedEncoder.stop();
					CaptureDiagnostics.logEvent(
						"encoder_stop_completed",
						"duration_ms=" + formatDurationMs(System.nanoTime() - encoderStopStartedAt)
					);
				}
				CaptureDiagnostics.probeMedia("raw_video_after_encoder_stop", rawVideoPath);
				long awaitAudioStartedAt = System.nanoTime();
				AudioCaptureStart audioCaptureStart = awaitAudioCaptureStart(capturedAudioCaptureStartFuture);
				CaptureDiagnostics.logEvent(
					"audio_start_awaited",
					"duration_ms=" + formatDurationMs(System.nanoTime() - awaitAudioStartedAt)
						+ " has_session=" + (audioCaptureStart != null && audioCaptureStart.hasSession())
				);
				long audioStopStartedAt = System.nanoTime();
				StoppedCaptureArtifacts stopArtifacts = stopPlatformAudioCapture(audioCaptureStart);
				CaptureDiagnostics.logEvent(
					"audio_stop_completed",
					"duration_ms=" + formatDurationMs(System.nanoTime() - audioStopStartedAt)
				);
				List<Path> audioTracks = stopArtifacts.audioTracks();
				CaptureDiagnostics.logAudioTrackSummary(audioTracks);
				for (int i = 0; i < audioTracks.size(); i++) {
					CaptureDiagnostics.probeMedia("audio_track_" + i, audioTracks.get(i));
				}
				audioTracksAvailable = !audioTracks.isEmpty();
				if (stopArtifacts.directVideoPath() != null) {
					Path directVideoPath = stopArtifacts.directVideoPath();
					recoveredDirectVideoPath = directVideoPath;
					CaptureDiagnostics.probeMedia(stopArtifacts.directVideoLabel(), directVideoPath);
					CaptureDiagnostics.logEvent(
						"finalize_direct_video",
						"direct_video_path=" + BlockChatLogPrivacy.privacySafePath(directVideoPath)
							+ " audio_tracks_count=" + audioTracks.size()
							+ " audio_tracks_available=" + audioTracksAvailable
					);
					// The SCRecordingOutput MP4 already contains system audio but NOT
					// the microphone sidecar WAV. If we have a mic WAV, mix it into
					// the direct video's existing audio so the final clip has both.
					Path finalClipPath = directVideoPath;
					// Filter to only mic sidecar tracks (skip system audio WAV since
					// the direct video already has system audio baked in by SCRecordingOutput)
					List<Path> micSidecarTracks = new ArrayList<>();
					for (Path track : audioTracks) {
						String name = track.getFileName().toString().toLowerCase(Locale.ROOT);
						if (!name.contains("system-audio") && !name.contains("system_audio")) {
							micSidecarTracks.add(track);
						}
					}
					StringBuilder micTrackPrivacy = new StringBuilder();
					for (Path p : micSidecarTracks) {
						if (micTrackPrivacy.length() > 0) {
							micTrackPrivacy.append(',');
						}
						micTrackPrivacy.append(BlockChatLogPrivacy.privacySafePath(p));
					}
					CaptureDiagnostics.logEvent(
						"mic_sidecar_filter",
						"total_audio_tracks=" + audioTracks.size()
							+ " mic_sidecar_tracks=" + micSidecarTracks.size()
							+ " mic_tracks=" + micTrackPrivacy
					);
					if (!micSidecarTracks.isEmpty() && FFmpegLocator.isAvailable()) {
						try {
							long muxStartedAt = System.nanoTime();
							Path muxedOutput = captureWorkingDirectory.resolve("muxed-with-mic.mp4");
							AudioMuxer.muxWithExistingAudio(directVideoPath, micSidecarTracks, muxedOutput);
							// Replace the direct video with the muxed version
							Files.deleteIfExists(directVideoPath);
							Files.move(muxedOutput, directVideoPath);
							finalClipPath = directVideoPath;
							CaptureDiagnostics.logEvent(
								"mic_mux_into_direct_video",
								"success duration_ms=" + formatDurationMs(System.nanoTime() - muxStartedAt)
									+ " mic_tracks=" + micSidecarTracks.size()
							);
						} catch (Exception muxError) {
							BlockChatClientMod.LOGGER.warn(
								"Failed to mux mic audio into direct video, using original",
								muxError
							);
							CaptureDiagnostics.logEvent(
								"mic_mux_into_direct_video",
								"failed message=" + muxError.getMessage()
							);
							finalClipPath = directVideoPath;
						}
					} else if (!micSidecarTracks.isEmpty()) {
						CaptureDiagnostics.logEvent(
							"mic_mux_skipped",
							"ffmpeg_available=false mic_tracks=" + micSidecarTracks.size()
						);
					}
					CaptureDiagnostics.probeMedia("final_output", finalClipPath);
					publishCompletedPendingClip(
						finalClipPath,
						captureWorkingDirectory,
						audioTracks
					);
				} else {
					long finalizeClipStartedAt = System.nanoTime();
					Path completedClipPath = finalizeClip(
						effectiveRawVideoPath,
						targetClipPath,
						audioTracks
					);
					CaptureDiagnostics.logEvent(
						"finalize_clip_completed",
						"duration_ms=" + formatDurationMs(System.nanoTime() - finalizeClipStartedAt)
							+ " audio_tracks_available=" + audioTracksAvailable
					);
					CaptureDiagnostics.probeMedia("final_output", completedClipPath);
					publishCompletedPendingClip(
						completedClipPath,
						captureWorkingDirectory,
						audioTracks
					);
				}
			} catch (Exception e) {
				audioFailed = true;
				BlockChatClientMod.LOGGER.error("Failed to finalize BlockChat clip audio", e);
				BlockChatDebugLog.line("capture", "finalize_recording_failed", e);
				CaptureDiagnostics.logEvent("finalize_error", "message=" + e.getMessage());
				try {
					if (effectiveRawVideoPath != null && targetClipPath != null) {
						AudioMuxer.copyVideo(effectiveRawVideoPath, targetClipPath);
						CaptureDiagnostics.probeMedia("recovered_silent_output", targetClipPath);
						publishCompletedPendingClip(
							targetClipPath,
							captureWorkingDirectory,
							List.of()
						);
					} else if (recoveredDirectVideoPath != null && Files.isRegularFile(recoveredDirectVideoPath)) {
						CaptureDiagnostics.probeMedia("recovered_direct_output", recoveredDirectVideoPath);
						publishCompletedPendingClip(
							recoveredDirectVideoPath,
							captureWorkingDirectory,
							List.of()
						);
					} else if (PendingCapture.isCurrentPendingClip(targetClipPath)) {
						PendingCapture.updatePendingClipAudioState(targetClipPath, List.of());
					} else if (targetClipPath != null && effectiveRawVideoPath != null) {
						PendingCapture.setPendingClip(
							targetClipPath,
							capturedWidth,
							capturedHeight,
							captureWorkingDirectory,
							List.of()
						);
						} else {
							client.execute(() -> notifyCaptureUnavailable(
								client,
								isWindows()
									? BlockChatStrings.RECORDING_FAILED_WINDOWS_NO_FILE
									: BlockChatStrings.RECORDING_FAILED_MACOS_NO_FILE
							));
						}
				} catch (IOException moveError) {
					BlockChatClientMod.LOGGER.error("Failed to recover silent BlockChat clip", moveError);
					CaptureDiagnostics.logEvent("silent_recovery_error", "message=" + moveError.getMessage());
				}
			} finally {
				if (!PendingCapture.isCurrentPendingClip(targetClipPath)) {
					cleanupWorkingDirectory(captureWorkingDirectory);
				}
				CaptureDiagnostics.finishSession(audioFailed ? "failure" : "success");
			}

			client.execute(() -> {
				finishStoppingOnMainThread();
			});
		});
	}

	private static void finishStoppingOnMainThread() {
		state = State.IDLE;
	}

	private static void showStoppedRecordingToastOnMainThread(long elapsed, boolean clipProcessing) {
		String keyName = BlockChatClientMod.getOpenKeyDisplayName();
		int seconds = (int) (elapsed / 1000);
		String prefix = clipProcessing ? BlockChatStrings.FINISHING_CLIP_PREFIX : "";
		BlockChatHud.showCaptureToast(
			Component.literal(BlockChatStrings.clipSavedTitle(seconds)),
			Component.literal(buildClipToastSubtitle(keyName, prefix))
		);
		BlockChatClientMod.LOGGER.info("Recording stopped; BlockChat clip finalization continues in the background");
	}

	private static String buildClipToastSubtitle(String keyName, String prefix) {
		return prefix + BlockChatStrings.pressKeyToSendInBlockChat(keyName);
	}

	private static void publishCompletedPendingClip(
		Path completedClipPath,
		Path captureWorkingDirectory,
		List<Path> audioTracks
	) {
		PendingCapture.setPendingClip(
			completedClipPath,
			capturedWidth,
			capturedHeight,
			captureWorkingDirectory,
			audioTracks
		);
	}

	private static CompletableFuture<AudioCaptureStart> startPlatformAudioCaptureAsync(
		Minecraft client,
		Path captureWorkingDirectory,
		Path targetClipPath,
		boolean useNativeHelperVideoCapture
	) {
		if (captureWorkingDirectory == null) {
			return null;
		}
		if (isWindows()) {
			CaptureDiagnostics.logEvent("audio_start_scheduled", "platform=windows");
			if (useNativeHelperVideoCapture) {
				return CompletableFuture.supplyAsync(() -> startWindowsNativeCapture(
					client,
					captureWorkingDirectory,
					targetClipPath
				));
			}
			CaptureDiagnostics.logEvent(
				"audio_start_skipped",
				"platform=windows reason=helper_required"
			);
			return CompletableFuture.completedFuture(AudioCaptureStart.none());
		}
		if (MacOsCaptureHelperLocator.isSupportedPlatform()) {
			CaptureDiagnostics.logEvent("audio_start_scheduled", "platform=macos");
			return CompletableFuture.supplyAsync(() -> startMacOsAudioCapture(client, captureWorkingDirectory, targetClipPath));
		}
		CaptureDiagnostics.logEvent("audio_start_skipped", "platform=unsupported");
		return null;
	}

	private static AudioCaptureStart startWindowsNativeCapture(
		Minecraft client,
		Path captureWorkingDirectory,
		Path targetClipPath
	) {
		long startedAt = System.nanoTime();
		WindowsNativeCaptureHelperSession nativeSession = null;
		try {
			if (!WindowsNativeCaptureHelperLocator.isHelperBuilt() && !WindowsNativeCaptureHelperLocator.hasBuildScript()) {
				notifyCaptureUnavailable(client, BlockChatStrings.WINDOWS_HELPER_UNAVAILABLE_BUILD);
				BlockChatClientMod.LOGGER.warn("BlockChat Windows capture helper is unavailable");
				CaptureDiagnostics.logEvent(
					"audio_start_result",
					"platform=windows duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
						+ " has_session=false reason=helper_unavailable"
				);
				return AudioCaptureStart.none();
			}
			nativeSession = new WindowsNativeCaptureHelperSession();
			Path videoPath = targetClipPath != null
				? targetClipPath
				: captureWorkingDirectory.resolve("windows-native-capture.mp4");
			nativeSession.start(new WindowsNativeCaptureHelperSession.Config(
				videoPath,
				BlockChatConfig.getPreferredMicDeviceId(),
				BlockChatConfig.getPreferredMicDeviceName(),
				BlockChatConfig.isMicEnabled(),
				ProcessHandle.current().pid(),
				BlockChatVideoConstants.CAPTURE_FPS,
				WINDOWS_MAX_CAPTURE_HEIGHT,
				null,
				captureWorkingDirectory.resolve("windows-helper.log"),
				captureWorkingDirectory.resolve("windows-ready.json"),
				captureWorkingDirectory.resolve("windows-stop.flag"),
				null
			));
			CaptureDiagnostics.logEvent(
				"audio_start_result",
				"platform=windows duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
					+ " has_session=true native_helper=true mic_enabled=" + BlockChatConfig.isMicEnabled()
			);
			return AudioCaptureStart.forWindowsNative(nativeSession);
		} catch (Exception e) {
			if (nativeSession != null) {
				nativeSession.stop();
			}
			notifyCaptureUnavailable(client, BlockChatStrings.WINDOWS_CAPTURE_UNAVAILABLE);
			BlockChatClientMod.LOGGER.warn("BlockChat Windows native capture unavailable", e);
			CaptureDiagnostics.logEvent(
				"audio_start_result",
				"platform=windows duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
					+ " has_session=false native_helper=true message=" + e.getMessage()
			);
			return AudioCaptureStart.none();
		}
	}

	private static AudioCaptureStart startMacOsAudioCapture(
		Minecraft client,
		Path captureWorkingDirectory,
		Path targetClipPath
	) {
		long startedAt = System.nanoTime();
		try {
			if (!MacOsCaptureHelperLocator.isHelperBuilt() && !MacOsCaptureHelperLocator.hasBuildScript()) {
				notifyCaptureUnavailable(client, BlockChatStrings.MACOS_HELPER_UNAVAILABLE);
				BlockChatClientMod.LOGGER.warn("BlockChat macOS capture helper is unavailable");
				CaptureDiagnostics.logEvent(
					"audio_start_result",
					"platform=macos duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
						+ " has_session=false reason=helper_unavailable"
				);
				return AudioCaptureStart.none();
			}
			MacOsCaptureHelperSession session = new MacOsCaptureHelperSession();
			Path videoPath = targetClipPath != null
				? targetClipPath
				: captureWorkingDirectory.resolve("macos-capture.mp4");
			Path systemAudioPath = captureWorkingDirectory.resolve("macos-system-audio.wav");
			boolean micEnabled = BlockChatConfig.isMicEnabled();
			String micDeviceId = BlockChatConfig.getPreferredMicDeviceId();
			String micDeviceName = BlockChatConfig.getPreferredMicDeviceName();
			Path microphonePath = micEnabled
				? captureWorkingDirectory.resolve("macos-microphone.wav")
				: null;
			boolean micPrefSet = (micDeviceId != null && !micDeviceId.isBlank())
				|| (micDeviceName != null && !micDeviceName.isBlank());
			CaptureDiagnostics.logEvent(
				"macos_mic_config",
				"mic_enabled=" + micEnabled
					+ " mic_preference_set=" + micPrefSet
					+ " mic_output_path=" + BlockChatLogPrivacy.privacySafePath(microphonePath)
					+ " video_path=" + BlockChatLogPrivacy.privacySafePath(videoPath)
					+ " system_audio_path=" + BlockChatLogPrivacy.privacySafePath(systemAudioPath)
			);
			BlockChatClientMod.LOGGER.info(
				"BlockChat macOS mic config: enabled={} deviceId={} deviceName={} micPath={}",
				micEnabled, micDeviceId, micDeviceName, microphonePath
			);
			session.start(new MacOsCaptureHelperSession.Config(
				videoPath,
				systemAudioPath,
				microphonePath,
				micDeviceId,
				ProcessHandle.current().pid(),
				MACOS_MAX_CAPTURE_HEIGHT,
				captureWorkingDirectory.resolve("macos-capture-helper.log"),
				captureWorkingDirectory.resolve("macos-capture-helper-ready.json"),
				null
			));
			CaptureDiagnostics.logEvent(
				"audio_start_result",
				"platform=macos duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
					+ " has_session=true mic_enabled=" + micEnabled
					+ " mic_preference_set=" + micPrefSet
			);
			return AudioCaptureStart.forMac(session, systemAudioPath, microphonePath);
		} catch (Exception e) {
			notifyCaptureUnavailable(client, BlockChatStrings.MACOS_CAPTURE_UNAVAILABLE);
			BlockChatClientMod.LOGGER.warn("BlockChat macOS capture unavailable", e);
			CaptureDiagnostics.logEvent(
				"audio_start_result",
				"platform=macos duration_ms=" + formatDurationMs(System.nanoTime() - startedAt)
					+ " has_session=false message=" + e.getMessage()
			);
			return AudioCaptureStart.none();
		}
	}

	private static void notifyCaptureUnavailable(Minecraft client, String message) {
		client.execute(() -> {
			if (client.player != null) {
				BlockChatPlayerMessages.show(
					client.player,
					Component.literal(BlockChatStrings.prefixedMessage(message)),
					false
				);
			}
		});
	}

	private static boolean isAudioCaptureProcessing(
		CompletableFuture<AudioCaptureStart> capturedAudioCaptureStartFuture
	) {
		if (capturedAudioCaptureStartFuture == null) {
			return false;
		}
		try {
			AudioCaptureStart immediate = capturedAudioCaptureStartFuture.getNow(null);
			return immediate == null || immediate.hasSession();
		} catch (CompletionException ignored) {
			return false;
		}
	}

	private static AudioCaptureStart awaitAudioCaptureStart(
		CompletableFuture<AudioCaptureStart> capturedAudioCaptureStartFuture
	) {
		if (capturedAudioCaptureStartFuture == null) {
			return AudioCaptureStart.none();
		}
		try {
			AudioCaptureStart result = capturedAudioCaptureStartFuture.join();
			return result != null ? result : AudioCaptureStart.none();
		} catch (CompletionException e) {
			BlockChatClientMod.LOGGER.warn("BlockChat audio startup future failed", e);
			BlockChatDebugLog.line("capture", "audio_startup_future_failed", e);
			return AudioCaptureStart.none();
		}
	}

	private static AudioCaptureStart getImmediateAudioCaptureStart(
		CompletableFuture<AudioCaptureStart> capturedAudioCaptureStartFuture
	) {
		if (capturedAudioCaptureStartFuture == null) {
			return null;
		}
		try {
			return capturedAudioCaptureStartFuture.getNow(null);
		} catch (CompletionException ignored) {
			return AudioCaptureStart.none();
		}
	}

	private static StoppedCaptureArtifacts stopPlatformAudioCapture(AudioCaptureStart audioCaptureStart) {
		List<Path> audioTracks = new ArrayList<>();
		if (audioCaptureStart == null) {
			CaptureDiagnostics.logEvent("stop_audio_capture", "no_audio_capture_start");
			return StoppedCaptureArtifacts.none(audioTracks);
		}

		if (audioCaptureStart.windowsNativeSession() != null) {
			audioCaptureStart.windowsNativeSession().stop();
			WindowsNativeCaptureArtifacts artifacts = audioCaptureStart.windowsNativeSession().getArtifacts();
			if (artifacts != null) {
				return StoppedCaptureArtifacts.direct(
					audioTracks,
					artifacts.videoPath(),
					"windows_helper_video"
				);
			}
		}

		if (audioCaptureStart.macOsSession() != null) {
			audioCaptureStart.macOsSession().stop();
			MacOsCaptureArtifacts artifacts = audioCaptureStart.macOsSession().getArtifacts();
			boolean micPrefStop = (BlockChatConfig.getPreferredMicDeviceId() != null
				&& !BlockChatConfig.getPreferredMicDeviceId().isBlank())
				|| (BlockChatConfig.getPreferredMicDeviceName() != null
				&& !BlockChatConfig.getPreferredMicDeviceName().isBlank());
			CaptureDiagnostics.logEvent(
				"macos_audio_stop",
				"artifacts_present=" + (artifacts != null)
					+ " mic_enabled=" + BlockChatConfig.isMicEnabled()
					+ " mic_preference_set=" + micPrefStop
			);
			if (artifacts != null) {
				CaptureDiagnostics.logEvent(
					"macos_audio_artifacts",
					"video_path=" + BlockChatLogPrivacy.privacySafePath(artifacts.videoPath())
						+ " system_audio_path=" + BlockChatLogPrivacy.privacySafePath(artifacts.systemAudioPath())
						+ " mic_path=" + BlockChatLogPrivacy.privacySafePath(artifacts.microphonePath())
						+ " helper_log=" + BlockChatLogPrivacy.privacySafePath(artifacts.helperLogPath())
				);
				logAudioFileDebug("macos_system_audio", artifacts.systemAudioPath());
				logAudioFileDebug("macos_microphone", artifacts.microphonePath());
				addAudioTrackIfPresent(audioTracks, artifacts.systemAudioPath());
				addAudioTrackIfPresent(audioTracks, artifacts.microphonePath());
				CaptureDiagnostics.logEvent(
					"macos_audio_tracks_collected",
					"count=" + audioTracks.size()
						+ " direct_video=" + BlockChatLogPrivacy.privacySafePath(artifacts.videoPath())
				);
				// Log the helper output for debugging
				logHelperOutput(artifacts.helperLogPath());
				return StoppedCaptureArtifacts.direct(
					audioTracks,
					artifacts.videoPath(),
					"macos_helper_video"
				);
			} else {
				CaptureDiagnostics.logEvent(
					"macos_audio_artifacts_null",
					"falling_back_to_session_paths"
						+ " system_audio=" + BlockChatLogPrivacy.privacySafePath(audioCaptureStart.macOsSystemAudioPath())
						+ " mic=" + BlockChatLogPrivacy.privacySafePath(audioCaptureStart.macOsMicrophonePath())
				);
				logAudioFileDebug("macos_fallback_system_audio", audioCaptureStart.macOsSystemAudioPath());
				logAudioFileDebug("macos_fallback_microphone", audioCaptureStart.macOsMicrophonePath());
				addAudioTrackIfPresent(audioTracks, audioCaptureStart.macOsSystemAudioPath());
				addAudioTrackIfPresent(audioTracks, audioCaptureStart.macOsMicrophonePath());
			}
		}

		return StoppedCaptureArtifacts.none(audioTracks);
	}

	private static void logAudioFileDebug(String label, Path audioPath) {
		if (audioPath == null) {
			CaptureDiagnostics.logEvent("audio_file_debug", "label=" + label + " path=null");
			return;
		}
		try {
			boolean exists = Files.isRegularFile(audioPath);
			long size = exists ? Files.size(audioPath) : -1;
			CaptureDiagnostics.logEvent(
				"audio_file_debug",
				"label=" + label
					+ " exists=" + exists
					+ " size_bytes=" + size
					+ " path=" + BlockChatLogPrivacy.privacySafePath(audioPath)
					+ " would_include=" + (exists && size > 44L)
			);
		} catch (IOException e) {
			CaptureDiagnostics.logEvent(
				"audio_file_debug",
				"label=" + label + " error=" + e.getMessage() + " path=" + BlockChatLogPrivacy.privacySafePath(audioPath)
			);
		}
	}

	private static void logHelperOutput(Path helperLogPath) {
		if (helperLogPath == null || !Files.isRegularFile(helperLogPath)) {
			return;
		}
		try {
			String content = Files.readString(helperLogPath, java.nio.charset.StandardCharsets.UTF_8);
			if (!content.isBlank()) {
				CaptureDiagnostics.logEvent("macos_helper_log_start", "---");
				for (String line : content.split("\n")) {
					if (!line.isBlank()) {
						CaptureDiagnostics.logEvent("macos_helper_log", BlockChatLogPrivacy.redactMacosHelperLogLine(line.trim()));
					}
				}
				CaptureDiagnostics.logEvent("macos_helper_log_end", "---");
			}
		} catch (IOException e) {
			CaptureDiagnostics.logEvent("macos_helper_log_read_error", e.getMessage());
		}
	}

	private static void addAudioTrackIfPresent(List<Path> audioTracks, Path audioPath) {
		if (audioPath == null) {
			CaptureDiagnostics.logEvent("add_audio_track", "skipped reason=null_path");
			return;
		}
		try {
			boolean isFile = Files.isRegularFile(audioPath);
			long size = isFile ? Files.size(audioPath) : -1;
			if (isFile && size > 44L) {
				audioTracks.add(audioPath);
				CaptureDiagnostics.logEvent(
					"add_audio_track",
					"added path=" + audioPath.getFileName() + " size_bytes=" + size
				);
			} else {
				CaptureDiagnostics.logEvent(
					"add_audio_track",
					"skipped path=" + audioPath.getFileName()
						+ " is_file=" + isFile
						+ " size_bytes=" + size
						+ " reason=" + (!isFile ? "not_a_file" : "too_small_likely_empty_wav_header")
				);
			}
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.debug("Skipping unreadable BlockChat audio track {}", audioPath, e);
			CaptureDiagnostics.logEvent(
				"add_audio_track",
				"skipped path=" + audioPath.getFileName() + " error=" + e.getMessage()
			);
		}
	}

	private static Path finalizeClip(Path rawVideoPath, Path targetClipPath, List<Path> audioTracks) throws IOException {
		if (rawVideoPath == null || !Files.isRegularFile(rawVideoPath)) {
			throw new IOException("Missing BlockChat raw video for finalization");
		}
		if (targetClipPath == null) {
			return rawVideoPath;
		}
		AudioMuxer.finalizeClip(rawVideoPath, audioTracks, targetClipPath);
		return targetClipPath;
	}

	private static void submitCapturedFrame(byte[] frame, long captureTimeNanos) {
		if (frame == null || encoder == null || !encoder.isRunning()) {
			return;
		}
		long captureOffsetNanos = Math.max(0L, captureTimeNanos - recordingStartNanos);
		long targetFrameIndex = captureOffsetNanos / BlockChatVideoConstants.FRAME_INTERVAL_NS;

		if (lastCapturedFrame == null) {
			int initialFrameCount = safeRepeatCount(targetFrameIndex + 1L);
			if (initialFrameCount > 0) {
				encoder.submitFrame(frame, initialFrameCount);
				nextEncodedFrameIndex += initialFrameCount;
			}
			lastCapturedFrame = frame;
			return;
		}

		long paddingBeforeCurrent = targetFrameIndex - nextEncodedFrameIndex;
		if (paddingBeforeCurrent > 0L) {
			int fillerCount = safeRepeatCount(paddingBeforeCurrent);
			encoder.submitFrame(lastCapturedFrame, fillerCount);
			nextEncodedFrameIndex += fillerCount;
		}

		if (targetFrameIndex >= nextEncodedFrameIndex) {
			encoder.submitFrame(frame);
			nextEncodedFrameIndex++;
		}

		lastCapturedFrame = frame;
	}

	private static void padVideoTimeline(
		FFmpegEncoder capturedEncoder,
		byte[] lastFrameForPadding,
		long nextFrameIndexForPadding,
		long elapsedNanos
	) throws InterruptedException {
		if (capturedEncoder == null || lastFrameForPadding == null || elapsedNanos <= 0L) {
			return;
		}
		long targetFrameCount = ceilDiv(elapsedNanos, BlockChatVideoConstants.FRAME_INTERVAL_NS);
		long paddingFrames = targetFrameCount - nextFrameIndexForPadding;
		if (paddingFrames <= 0L) {
			return;
		}
		int repeatCount = safeRepeatCount(paddingFrames);
		capturedEncoder.submitFrameBlocking(lastFrameForPadding, repeatCount, 5_000L);
		CaptureDiagnostics.logEvent(
			"video_timeline_padding",
			"padding_frames=" + repeatCount
				+ " target_frame_count=" + targetFrameCount
				+ " encoded_before_padding=" + nextFrameIndexForPadding
		);
	}

	private static long ceilDiv(long numerator, long denominator) {
		if (numerator <= 0L) {
			return 0L;
		}
		return ((numerator - 1L) / denominator) + 1L;
	}

	private static int safeRepeatCount(long count) {
		if (count <= 0L) {
			return 0;
		}
		return (int) Math.min(Integer.MAX_VALUE, count);
	}

	private static void cleanupWorkingDirectory(Path captureWorkingDirectory) {
		if (captureWorkingDirectory == null || !Files.exists(captureWorkingDirectory)) {
			return;
		}
		try (var paths = Files.walk(captureWorkingDirectory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					BlockChatClientMod.LOGGER.debug("Failed to delete BlockChat temp path {}", path, e);
				}
			});
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.debug("Failed to clean BlockChat capture working directory", e);
		}
	}

	private static String formatDurationMs(long durationNs) {
		return String.format(Locale.ROOT, "%.3f", durationNs / 1_000_000.0D);
	}

	private static int[] computeCappedCaptureSize(int sourceWidth, int sourceHeight, int maxHeight) {
		if (sourceHeight <= maxHeight) {
			return new int[]{sourceWidth, sourceHeight};
		}
		double scale = (double) maxHeight / (double) sourceHeight;
		int scaledWidth = Math.max(2, (int) Math.round(sourceWidth * scale));
		int evenWidth = scaledWidth % 2 == 0 ? scaledWidth : scaledWidth - 1;
		return new int[]{Math.max(2, evenWidth), maxHeight};
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "")
			.toLowerCase(Locale.ROOT)
			.contains("win");
	}

	private record AudioCaptureStart(
		WindowsNativeCaptureHelperSession windowsNativeSession,
		MacOsCaptureHelperSession macOsSession,
		Path macOsSystemAudioPath,
		Path macOsMicrophonePath
	) {
		private static AudioCaptureStart none() {
			return new AudioCaptureStart(null, null, null, null);
		}

		private static AudioCaptureStart forWindowsNative(WindowsNativeCaptureHelperSession windowsNativeSession) {
			return new AudioCaptureStart(windowsNativeSession, null, null, null);
		}

		private static AudioCaptureStart forMac(
			MacOsCaptureHelperSession macOsSession,
			Path macOsSystemAudioPath,
			Path macOsMicrophonePath
		) {
			return new AudioCaptureStart(null, macOsSession, macOsSystemAudioPath, macOsMicrophonePath);
		}

		private boolean hasSession() {
			return windowsNativeSession != null || macOsSession != null;
		}
	}

	private record StoppedCaptureArtifacts(
		List<Path> audioTracks,
		Path directVideoPath,
		String directVideoLabel
	) {
		private static StoppedCaptureArtifacts none(List<Path> audioTracks) {
			return new StoppedCaptureArtifacts(audioTracks, null, null);
		}

		private static StoppedCaptureArtifacts direct(
			List<Path> audioTracks,
			Path directVideoPath,
			String directVideoLabel
		) {
			return new StoppedCaptureArtifacts(audioTracks, directVideoPath, directVideoLabel);
		}
	}
}
