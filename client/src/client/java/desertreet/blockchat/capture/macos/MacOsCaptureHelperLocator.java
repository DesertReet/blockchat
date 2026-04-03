package desertreet.blockchat.capture.macos;

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
 * Locates the isolated macOS ScreenCaptureKit helper and its build script.
 * Shared recorder wiring decides when to invoke it.
 */
public final class MacOsCaptureHelperLocator {

	private static final String HELPER_DIR_PROPERTY = "blockchat.macosCaptureHelperDir";
	private static final String HELPER_DIR_ENV = "BLOCKCHAT_MACOS_CAPTURE_HELPER_DIR";
	private static final String HELPER_BINARY_NAME = "BlockChatMacCaptureHelper";
	private static final String BUNDLED_HELPER_RESOURCE_ROOT = "blockchat-native/bins/macos";
	private static final String BUNDLED_BINARY_RESOURCE = HELPER_BINARY_NAME;

	private MacOsCaptureHelperLocator() {
	}

	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name", "")
			.toLowerCase(Locale.ROOT)
			.contains("mac");
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

		Path repoHelperDir = BlockChatPaths.getRepoNativeHelperDirectory("macos-capture-helper");
		if (Files.isDirectory(repoHelperDir)) {
			return repoHelperDir;
		}

		return BlockChatPaths.getRuntimeRoot();
	}

	public static Path getBuildScriptPath() {
		Path helperDirectory = getHelperDirectory();
		ensureBundledHelperInstalled(helperDirectory);
		return helperDirectory.resolve("build-helper.sh");
	}

	public static Path getBinaryPath() {
		Path helperDirectory = getHelperDirectory();
		ensureBundledHelperInstalled(helperDirectory);
		if (usesRepoHelperDirectory(helperDirectory)) {
			return helperDirectory.resolve(".build").resolve("release").resolve(HELPER_BINARY_NAME);
		}
		return BlockChatPaths.getRuntimeBinaryPath(HELPER_BINARY_NAME);
	}

	public static boolean hasBuildScript() {
		return Files.isRegularFile(getBuildScriptPath());
	}

	public static boolean isHelperBuilt() {
		return Files.isExecutable(getBinaryPath());
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
				"Failed to install bundled BlockChat macOS helper binary into {}",
				helperDirectory,
				e
			);
		}
	}

	private static boolean usesRepoHelperDirectory(Path helperDirectory) {
		return BlockChatPaths.usesRepoNativeHelperDirectory(helperDirectory, "macos-capture-helper");
	}

	private static boolean hasBundledHelperBinary() {
		ClassLoader loader = MacOsCaptureHelperLocator.class.getClassLoader();
		return loader.getResource(BUNDLED_HELPER_RESOURCE_ROOT + "/" + BUNDLED_BINARY_RESOURCE) != null;
	}

	private static void copyBundledResource(Path helperDirectory, String resourcePath, String binaryName) throws IOException {
		String fullResourcePath = BUNDLED_HELPER_RESOURCE_ROOT + "/" + resourcePath;
		ClassLoader loader = MacOsCaptureHelperLocator.class.getClassLoader();
		try (InputStream input = loader.getResourceAsStream(fullResourcePath)) {
			if (input == null) {
				throw new IOException("Missing bundled macOS helper binary: " + fullResourcePath);
			}

			Path targetPath = BlockChatPaths.getRuntimeBinaryPath(binaryName);
			BlockChatPaths.ensureRuntimeDirectory(targetPath.getParent());
			Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
			targetPath.toFile().setExecutable(true, false);
			BlockChatPaths.ensureHiddenBestEffort(targetPath);
		}
	}
}
