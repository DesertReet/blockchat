package desertreet.blockchat.capture.windows;

import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Locates the isolated Windows native capture helper and its build resources.
 * Like the macOS helper path, the Java recorder only owns extraction and launch.
 */
public final class WindowsNativeCaptureHelperLocator {

	private static final String HELPER_DIR_PROPERTY = "blockchat.windowsHelperDir";
	private static final String HELPER_DIR_ENV = "BLOCKCHAT_WINDOWS_HELPER_DIR";
	private static final String HELPER_BINARY_NAME = "BlockChatWindowsCaptureHelper.exe";
	private static final String BUNDLED_HELPER_RESOURCE_ROOT = "blockchat-native/bins/windows";
	private static final String BUNDLED_BINARY_RESOURCE = HELPER_BINARY_NAME;

	private WindowsNativeCaptureHelperLocator() {
	}

	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name", "")
			.toLowerCase(Locale.ROOT)
			.contains("win");
	}

	public static Path getHelperDirectory() {
		String property = System.getProperty(HELPER_DIR_PROPERTY);
		if (property != null && !property.isBlank()) {
			return Path.of(property);
		}
		String env = System.getenv(HELPER_DIR_ENV);
		if (env != null && !env.isBlank()) {
			return Path.of(env);
		}

		Path repoHelperDir = BlockChatPaths.getRepoNativeHelperDirectory("windows-capture-helper");
		if (Files.isDirectory(repoHelperDir)) {
			return repoHelperDir;
		}

		return BlockChatPaths.getRuntimeRoot();
	}

	public static Path getBuildScriptPath() {
		Path helperDirectory = getHelperDirectory();
		ensureBundledHelperInstalled(helperDirectory);
		return helperDirectory.resolve("build-helper.cmd");
	}

	public static Path getBinaryPath() {
		Path helperDirectory = getHelperDirectory();
		ensureBundledHelperInstalled(helperDirectory);
		if (usesRepoHelperDirectory(helperDirectory)) {
			return helperDirectory.resolve(".build").resolve("Release").resolve(HELPER_BINARY_NAME);
		}
		return BlockChatPaths.getRuntimeBinaryPath(HELPER_BINARY_NAME);
	}

	public static boolean hasBuildScript() {
		return Files.isRegularFile(getBuildScriptPath());
	}

	public static boolean isHelperBuilt() {
		return Files.isRegularFile(getBinaryPath());
	}

	public static void prepareBundledHelperAsync() {
		if (!isSupportedPlatform()) {
			return;
		}
		ensureBundledHelperInstalled(getHelperDirectory());
		CompletableFuture.runAsync(() -> ensureBundledHelperInstalled(getHelperDirectory()));
	}

	private static void ensureBundledHelperInstalled(Path helperDirectory) {
		if (helperDirectory == null || !hasBundledHelperBinary()) {
			return;
		}
		try {
			copyBundledResource(helperDirectory, BUNDLED_BINARY_RESOURCE, HELPER_BINARY_NAME);
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn(
				"Failed to install bundled BlockChat Windows helper binary into {}",
				helperDirectory,
				e
			);
		}
	}

	private static boolean usesRepoHelperDirectory(Path helperDirectory) {
		return BlockChatPaths.usesRepoNativeHelperDirectory(helperDirectory, "windows-capture-helper");
	}

	private static boolean hasBundledHelperBinary() {
		ClassLoader loader = WindowsNativeCaptureHelperLocator.class.getClassLoader();
		return loader.getResource(BUNDLED_HELPER_RESOURCE_ROOT + "/" + BUNDLED_BINARY_RESOURCE) != null;
	}

	private static void copyBundledResource(Path helperDirectory, String resourcePath, String binaryName) throws IOException {
		String fullResourcePath = BUNDLED_HELPER_RESOURCE_ROOT + "/" + resourcePath;
		ClassLoader loader = WindowsNativeCaptureHelperLocator.class.getClassLoader();
		try (InputStream input = loader.getResourceAsStream(fullResourcePath)) {
			if (input == null) {
				throw new IOException("Missing bundled Windows helper binary: " + fullResourcePath);
			}

			Path targetPath = BlockChatPaths.getRuntimeBinaryPath(binaryName);
			BlockChatPaths.ensureRuntimeDirectory(targetPath.getParent());
			Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
			targetPath.toFile().setExecutable(true, false);
			BlockChatPaths.ensureHiddenBestEffort(targetPath);
		}
	}
}
