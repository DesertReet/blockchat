package desertreet.blockchat.util;

import net.minecraft.client.Minecraft;
import desertreet.blockchat.BlockChatClientMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class BlockChatPaths {

	private static final String BLOCKCHAT_DIR = "blockchat";
	private static final String RUNTIME_DIR = ".blockchat";

	private BlockChatPaths() {
	}

	public static Path getBlockChatRoot() {
		return getGameDirectoryPath().resolve(BLOCKCHAT_DIR);
	}

	public static Path getRuntimeRoot() {
		return getGameDirectoryPath().resolve(RUNTIME_DIR);
	}

	public static Path getRuntimeBinsDirectory() {
		return getRuntimeRoot().resolve("bins");
	}

	public static Path getDataRoot() {
		return getRuntimeRoot();
	}

	public static Path getConfigPath() {
		return getBlockChatRoot().resolve("config.json");
	}

	/**
	 * Client-visible diagnostic logs ({@code debug.log}, {@code last-recording-debug.log}) live here
	 * next to {@link #getConfigPath()}, not under the hidden {@link #getRuntimeRoot()} tree.
	 */
	public static Path getClientLogDirectory() {
		return getBlockChatRoot();
	}

	/**
	 * Creates a directory tree without marking it hidden (use for visible {@code blockchat/} paths).
	 */
	public static Path ensureBlockChatDirectory(Path directory) throws IOException {
		if (directory == null) {
			return null;
		}
		Files.createDirectories(directory);
		return directory;
	}

	public static Path getRuntimePath(String first, String... more) {
		return getRuntimeRoot().resolve(Path.of(first, more));
	}

	public static Path getRuntimeBinaryPath(String binaryName) {
		return getRuntimeBinsDirectory().resolve(binaryName);
	}

	public static Path getGameDirectoryPath() {
		return getGameDirectory();
	}

	public static Path getRepoNativeHelperDirectory(String helperDirName) {
		return getRepoNativeRoot().resolve(helperDirName);
	}

	public static boolean usesRepoNativeHelperDirectory(Path directory, String helperDirName) {
		if (directory == null) {
			return false;
		}
		return normalize(directory).equals(normalize(getRepoNativeHelperDirectory(helperDirName)));
	}

	public static Path ensureRuntimeDirectory(Path directory) throws IOException {
		if (directory == null) {
			return null;
		}
		Files.createDirectories(directory);
		ensureHiddenBestEffort(directory);
		return directory;
	}

	public static void ensureHiddenBestEffort(Path path) {
		if (path == null || !isWindows()) {
			return;
		}
		try {
			var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class);
			if (dos != null && Files.exists(path)) {
				dos.setHidden(true);
			}
		} catch (Exception ignored) {
		}
	}

	public static void prepareRuntimeLayout() {
		try {
			ensureBlockChatDirectory(getBlockChatRoot());
			ensureRuntimeDirectory(getRuntimeRoot());
			ensureRuntimeDirectory(getRuntimeBinsDirectory());
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("Failed to prepare BlockChat runtime directory {}", getRuntimeRoot(), e);
		}
	}

	private static Path getRepoNativeRoot() {
		return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
			.resolve("modules")
			.resolve("blockchat")
			.resolve("native");
	}

	private static Path getGameDirectory() {
		try {
			Minecraft client = Minecraft.getInstance();
			if (client != null && client.gameDirectory != null) {
				return client.gameDirectory.toPath();
			}
		} catch (Exception ignored) {
		}
		return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
