package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FFmpegLocator {

	private static volatile String ffmpegPath;
	private static volatile boolean detectionDone;
	private static final String BUNDLED_RESOURCE_ROOT = "blockchat-native/bins";

	private static final String[] FALLBACK_PATHS = {
		"/opt/homebrew/bin/ffmpeg",
		"/usr/local/bin/ffmpeg",
		"/usr/bin/ffmpeg",
		"C:\\ffmpeg\\bin\\ffmpeg.exe"
	};

	private FFmpegLocator() {
	}

	public static void detectAsync() {
		CompletableFuture.runAsync(() -> {
			try {
				String found = detect();
				ffmpegPath = found;
				if (found != null) {
					BlockChatClientMod.LOGGER.info("FFmpeg found at: {}", found);
				} else {
					BlockChatClientMod.LOGGER.warn("FFmpeg not found -- video recording disabled");
				}
			} finally {
				detectionDone = true;
			}
		});
	}

	public static boolean isAvailable() {
		return detectionDone && ffmpegPath != null;
	}

	public static boolean isDetectionDone() {
		return detectionDone;
	}

	public static String getPath() {
		return ffmpegPath;
	}

	private static String detect() {
		// 1. System property override
		String prop = System.getProperty("blockchat.ffmpeg");
		if (prop != null && !prop.isBlank() && Files.isExecutable(Path.of(prop))) {
			BlockChatClientMod.LOGGER.info("FFmpeg: using system property override: {}", prop);
			return prop;
		}

		// 2. Environment variable override
		String env = System.getenv("FFMPEG_BIN");
		if (env != null && !env.isBlank() && Files.isExecutable(Path.of(env))) {
			BlockChatClientMod.LOGGER.info("FFmpeg: using FFMPEG_BIN env var: {}", env);
			return env;
		}

		// 3. Bundled ffmpeg extracted into the hidden runtime bins directory
		String bundled = extractBundledFfmpeg();
		if (bundled != null) {
			BlockChatClientMod.LOGGER.info("FFmpeg: using bundled binary: {}", bundled);
			return bundled;
		}

		// 4. PATH search
		String fromPath = findOnPath();
		if (fromPath != null) {
			BlockChatClientMod.LOGGER.info("FFmpeg: found on PATH: {}", fromPath);
			return fromPath;
		}

		// 5. Hardcoded fallback paths
		for (String fallback : FALLBACK_PATHS) {
			if (Files.isExecutable(Path.of(fallback))) {
				BlockChatClientMod.LOGGER.info("FFmpeg: found at fallback path: {}", fallback);
				return fallback;
			}
		}

		return null;
	}

	/**
	 * Extracts the bundled ffmpeg binary from jar resources into the blockchat
	 * data directory. Returns the path to the extracted binary, or null if no
	 * bundled binary is available.
	 */
	private static String extractBundledFfmpeg() {
		String platformKey = resolveBundledPlatformKey();
		if (platformKey == null) {
			return null;
		}
		String resourceRoot = BUNDLED_RESOURCE_ROOT + "/" + platformKey;
		String binaryName = isWindows() ? "ffmpeg.exe" : "ffmpeg";
		String ffmpegResourceName = resourceRoot + "/" + binaryName;

		ClassLoader loader = FFmpegLocator.class.getClassLoader();
		if (loader.getResource(ffmpegResourceName) == null) {
			BlockChatClientMod.LOGGER.debug("FFmpeg: no bundled binary resources found at {}", resourceRoot);
			return null;
		}

		Path extractDir = getBundledFfmpegDir();
		Path targetPath = extractDir.resolve(binaryName);

		// Extract from jar
		try {
			BlockChatPaths.ensureRuntimeDirectory(extractDir);
			extractBundledBinary(loader, ffmpegResourceName, targetPath);
			if (!Files.isExecutable(targetPath)) {
				BlockChatClientMod.LOGGER.debug("FFmpeg: bundled ffmpeg not yet available at {}", targetPath);
				return null;
			}
			BlockChatClientMod.LOGGER.info("FFmpeg: extracted bundled ffmpeg to {}", targetPath);
			return targetPath.toString();
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("FFmpeg: failed to extract bundled binary to {}", targetPath, e);
			return null;
		}
	}

	private static void extractBundledBinary(ClassLoader loader, String resourceName, Path targetPath) throws IOException {
		if (Files.isExecutable(targetPath)) {
			return;
		}
		if (loader.getResource(resourceName) == null) {
			return;
		}
		try (InputStream input = loader.getResourceAsStream(resourceName)) {
			if (input == null) {
				return;
			}
			Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
		targetPath.toFile().setExecutable(true, false);
		BlockChatPaths.ensureHiddenBestEffort(targetPath);
		BlockChatClientMod.LOGGER.debug("FFmpeg: extracted bundled binary {} to {}", resourceName, targetPath);
	}

	private static String resolveBundledPlatformKey() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			return "win64";
		}
		if (osName.contains("mac")) {
			return (arch.contains("aarch64") || arch.contains("arm")) ? "macos-arm64" : "macos-amd64";
		}
		return null;
	}

	private static Path getBundledFfmpegDir() {
		return BlockChatPaths.getRuntimeBinsDirectory();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static String findOnPath() {
		boolean isWindows = isWindows();
		String[] cmd = isWindows
			? new String[]{"where", "ffmpeg"}
			: new String[]{"which", "ffmpeg"};

		try {
			Process proc = new ProcessBuilder(cmd)
				.redirectErrorStream(true)
				.start();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
				String line = reader.readLine();
				int exit = proc.waitFor();
				if (exit == 0 && line != null && !line.isBlank()) {
					String trimmed = line.trim();
					if (Files.isExecutable(Path.of(trimmed))) {
						return trimmed;
					}
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
