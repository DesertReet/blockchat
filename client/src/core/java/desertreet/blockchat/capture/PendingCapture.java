package desertreet.blockchat.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PendingCapture {

	public enum MediaType { IMAGE, VIDEO }

	/**
	 * Pending F2 screenshots are only offered to BlockChat if U is opened within this window.
	 */
	private static final long SCREENSHOT_PENDING_TTL_MS = 15_000L;

	private static Path pendingPath;
	private static MediaType pendingType;
	private static int pendingVideoWidth;
	private static int pendingVideoHeight;
	private static long pendingImageOfferedAtMs;
	private static volatile Path pendingClipWorkingDirectory;
	private static volatile List<Path> pendingClipAudioTracks = List.of();
	private static volatile Path discardedClipPath;

	private PendingCapture() {
	}

	/**
	 * Call at the start of each player F2 {@code Screenshot.grab} (fileName {@code null},
	 * downscale {@code 1}). Clears image-only pending (and its file); leaves video clips untouched.
	 */
	public static void onPlayerScreenshotGrabStarting() {
		if (pendingType == MediaType.IMAGE) {
			if (pendingPath != null) {
				try {
					Files.deleteIfExists(pendingPath);
				} catch (IOException ignored) {
				}
			}
			pendingPath = null;
			pendingType = null;
			pendingVideoWidth = 0;
			pendingVideoHeight = 0;
			pendingImageOfferedAtMs = 0L;
		}
	}

	/**
	 * Registers the BlockChat pending PNG path after vanilla has captured pixels (same frame as F2).
	 */
	public static void setPendingPlayerScreenshot(Path path) {
		deletePendingMediaFileIfDifferent(path);
		clearPendingMediaOnly();
		clearClipArtifactsOnly();
		pendingPath = path;
		pendingType = MediaType.IMAGE;
		pendingVideoWidth = 0;
		pendingVideoHeight = 0;
		pendingImageOfferedAtMs = System.currentTimeMillis();
	}

	public static void setPendingClip(
		Path path,
		int recordWidth,
		int recordHeight,
		Path workingDirectory,
		List<Path> audioTracks
	) {
		// Composer close should win over background finalization: do not resurrect a discarded draft clip.
		if (path != null && path.equals(discardedClipPath)) {
			deletePathIfExists(path);
			deleteWorkingDirectory(workingDirectory);
			discardedClipPath = null;
			return;
		}
		deletePendingMediaFileIfDifferent(path);
		clearPendingMediaOnly();
		clearClipArtifactsOnly();
		pendingPath = path;
		pendingType = MediaType.VIDEO;
		pendingVideoWidth = recordWidth;
		pendingVideoHeight = recordHeight;
		pendingImageOfferedAtMs = 0L;
		pendingClipWorkingDirectory = workingDirectory;
		pendingClipAudioTracks = audioTracks != null ? List.copyOf(audioTracks) : List.of();
	}

	public static boolean hasPending() {
		return pendingPath != null;
	}

	public static Path getPendingPath() {
		return pendingPath;
	}

	public static MediaType getPendingType() {
		return pendingType;
	}

	public static PendingMedia consumeIfReady() {
		if (pendingPath == null) {
			return null;
		}
		if (pendingType == MediaType.IMAGE && pendingImageOfferedAtMs != 0L) {
			long age = System.currentTimeMillis() - pendingImageOfferedAtMs;
			if (age > SCREENSHOT_PENDING_TTL_MS) {
				Path stale = pendingPath;
				clear();
				try {
					Files.deleteIfExists(stale);
				} catch (IOException ignored) {
				}
				return null;
			}
		}
		PendingMedia media = new PendingMedia(
			pendingPath, pendingType, pendingVideoWidth, pendingVideoHeight
		);
		if (pendingType == MediaType.IMAGE) {
			pendingPath = null;
			pendingType = null;
			pendingVideoWidth = 0;
			pendingVideoHeight = 0;
			pendingImageOfferedAtMs = 0L;
		}
		return media;
	}

	public static void clear() {
		if (pendingType == MediaType.VIDEO && pendingPath != null) {
			discardedClipPath = pendingPath;
		}
		deletePendingMediaFile();
		deleteWorkingDirectory(pendingClipWorkingDirectory);
		clearPendingMediaOnly();
		clearClipArtifactsOnly();
	}

	public static boolean hasClipAudioTracks() {
		return pendingClipAudioTracks != null && !pendingClipAudioTracks.isEmpty();
	}

	public static List<Path> getClipAudioTracks() {
		return pendingClipAudioTracks != null ? List.copyOf(pendingClipAudioTracks) : List.of();
	}

	public static boolean isCurrentPendingClip(Path clipPath) {
		return pendingType == MediaType.VIDEO
			&& pendingPath != null
			&& clipPath != null
			&& pendingPath.equals(clipPath);
	}

	public static boolean updatePendingClipAudioState(
		Path clipPath,
		List<Path> audioTracks
	) {
		if (!isCurrentPendingClip(clipPath)) {
			return false;
		}
		pendingClipAudioTracks = audioTracks != null ? List.copyOf(audioTracks) : List.of();
		return true;
	}

	private static void clearClipArtifactsOnly() {
		pendingClipWorkingDirectory = null;
		pendingClipAudioTracks = List.of();
	}

	private static void clearPendingMediaOnly() {
		pendingPath = null;
		pendingType = null;
		pendingVideoWidth = 0;
		pendingVideoHeight = 0;
		pendingImageOfferedAtMs = 0L;
	}

	private static void deletePendingMediaFile() {
		deletePathIfExists(pendingPath);
	}

	private static void deletePendingMediaFileIfDifferent(Path nextPath) {
		if (pendingPath == null) {
			return;
		}
		if (nextPath != null && nextPath.equals(pendingPath)) {
			return;
		}
		deletePendingMediaFile();
	}

	private static void deletePathIfExists(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private static void deleteWorkingDirectory(Path workingDirectory) {
		if (workingDirectory == null || !Files.exists(workingDirectory)) {
			return;
		}
		try (var paths = Files.walk(workingDirectory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	/**
	 * @param videoWidth  encoded frame width for {@link MediaType#VIDEO}; {@code 0} for images
	 * @param videoHeight encoded frame height for {@link MediaType#VIDEO}; {@code 0} for images
	 */
	public record PendingMedia(Path path, MediaType type, int videoWidth, int videoHeight) {
	}
}
