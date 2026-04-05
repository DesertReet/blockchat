package desertreet.blockchat.capture.windows;

import desertreet.blockchat.BlockChatClientMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Launcher and lifecycle bridge for the Windows native capture helper.
 *
 * The helper owns the authoritative Windows MP4. Java waits for readiness,
 * requests a graceful stop, and then publishes the helper-produced clip
 * directly without any Java-side audio replacement.
 */
public final class WindowsNativeCaptureHelperSession {

	private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(20);

	private Process helperProcess;
	private Path readyFile;
	private Path stopFile;
	private Path helperLogFile;
	private WindowsNativeCaptureArtifacts artifacts;

	public boolean isRunning() {
		return helperProcess != null && helperProcess.isAlive();
	}

	public void ensureBuilt() throws IOException, InterruptedException {
		if (!WindowsNativeCaptureHelperLocator.isSupportedPlatform()) {
			throw new IOException("Windows helper requested on non-Windows platform");
		}
		WindowsNativeCaptureHelperLocator.prepareBundledHelperAsync();
		if (WindowsNativeCaptureHelperLocator.isHelperBuilt()) {
			return;
		}
		Path script = WindowsNativeCaptureHelperLocator.getBuildScriptPath();
		if (!Files.isRegularFile(script)) {
			throw new IOException("BlockChat Windows helper build script not found: " + script);
		}
		Process process = new ProcessBuilder("cmd.exe", "/c", script.toString())
			.directory(WindowsNativeCaptureHelperLocator.getHelperDirectory().toFile())
			.inheritIO()
			.start();
		int exitCode = process.waitFor();
		if (exitCode != 0 || !WindowsNativeCaptureHelperLocator.isHelperBuilt()) {
			throw new IOException("Failed to build BlockChat Windows helper (exit " + exitCode + ")");
		}
	}

	public void start(Config config) throws IOException, InterruptedException {
		if (isRunning()) {
			return;
		}
		ensureBuilt();

		Files.createDirectories(config.videoOutput().getParent());
		helperLogFile = config.helperLogFile() != null
			? config.helperLogFile()
			: config.videoOutput().getParent().resolve("windows-helper.log");
		readyFile = config.readyFile() != null
			? config.readyFile()
			: config.videoOutput().getParent().resolve("windows-ready.json");
		stopFile = config.stopFile() != null
			? config.stopFile()
			: config.videoOutput().getParent().resolve("windows-stop.flag");
		Files.deleteIfExists(helperLogFile);
		Files.deleteIfExists(readyFile);
		Files.deleteIfExists(stopFile);

		artifacts = new WindowsNativeCaptureArtifacts(config.videoOutput(), helperLogFile);

		List<String> command = new ArrayList<>();
		command.add(WindowsNativeCaptureHelperLocator.getBinaryPath().toString());
		command.add("--video-out");
		command.add(config.videoOutput().toString());
		command.add("--ready-file");
		command.add(readyFile.toString());
		command.add("--stop-file");
		command.add(stopFile.toString());
		if (config.debugAudioDir() != null) {
			command.add("--debug-audio-dir");
			command.add(config.debugAudioDir().toString());
		}
		command.add("--capture-pid");
		command.add(Long.toString(config.capturePid()));
		command.add("--target-fps");
		command.add(Integer.toString(config.targetFps()));
		command.add("--max-video-height");
		command.add(Integer.toString(config.maxVideoHeight()));
		command.add("--mic-enabled");
		command.add(Boolean.toString(config.micEnabled()));
		if (config.microphoneDeviceId() != null && !config.microphoneDeviceId().isBlank()) {
			command.add("--microphone-device-id");
			command.add(config.microphoneDeviceId());
		}
		if (config.microphoneDeviceName() != null && !config.microphoneDeviceName().isBlank()) {
			command.add("--microphone-device-name");
			command.add(config.microphoneDeviceName());
		}

		helperProcess = new ProcessBuilder(command)
			.directory(WindowsNativeCaptureHelperLocator.getHelperDirectory().toFile())
			.redirectErrorStream(true)
			.redirectOutput(helperLogFile.toFile())
			.start();

		waitForReady(config.readyTimeout() != null ? config.readyTimeout() : DEFAULT_READY_TIMEOUT);
		BlockChatClientMod.LOGGER.info(
			"Started BlockChat Windows native capture helper: {}",
			WindowsNativeCaptureHelperLocator.getBinaryPath()
		);
	}

	public void stop() {
		if (helperProcess == null) {
			return;
		}
		Process process = helperProcess;
		helperProcess = null;
		try {
			if (stopFile != null) {
				Files.createDirectories(stopFile.getParent());
				Files.writeString(stopFile, "stop\n", StandardCharsets.UTF_8);
			}
			if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
		} catch (Exception e) {
			process.destroyForcibly();
			BlockChatClientMod.LOGGER.warn("Failed to stop BlockChat Windows helper cleanly", e);
		}
	}

	public WindowsNativeCaptureArtifacts getArtifacts() {
		return artifacts;
	}

	private void waitForReady(Duration timeout) throws IOException, InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (readyFile != null && Files.exists(readyFile)) {
				return;
			}
			if (helperProcess != null && !helperProcess.isAlive()) {
				throw new IOException(
					"BlockChat Windows helper exited before signaling readiness"
						+ summarizeHelperLogSuffix()
				);
			}
			Thread.sleep(100L);
		}
		throw new IOException(
			"Timed out waiting for BlockChat Windows helper ready file"
				+ summarizeHelperLogSuffix()
		);
	}

	private String summarizeHelperLogSuffix() {
		if (helperLogFile == null || !Files.isRegularFile(helperLogFile)) {
			return "";
		}
		try (BufferedReader reader = Files.newBufferedReader(helperLogFile, StandardCharsets.UTF_8)) {
			List<String> lines = reader.lines()
				.filter(line -> !line.isBlank())
				.collect(Collectors.toList());
			if (lines.isEmpty()) {
				return " (see " + helperLogFile + ")";
			}
			int start = Math.max(0, lines.size() - 3);
			String suffix = String.join(" | ", lines.subList(start, lines.size()));
			return " (" + suffix + "; see " + helperLogFile + ")";
		} catch (IOException ignored) {
			return " (see " + helperLogFile + ")";
		}
	}

	public record Config(
		Path videoOutput,
		String microphoneDeviceId,
		String microphoneDeviceName,
		boolean micEnabled,
		long capturePid,
		int targetFps,
		int maxVideoHeight,
		Path debugAudioDir,
		Path helperLogFile,
		Path readyFile,
		Path stopFile,
		Duration readyTimeout
	) {
	}
}
