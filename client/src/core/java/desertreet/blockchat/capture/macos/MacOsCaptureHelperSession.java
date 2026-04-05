package desertreet.blockchat.capture.macos;

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
 * Isolated launcher for the macOS ScreenCaptureKit helper.
 * Shared recorder code can start and stop this session without needing to know
 * about helper build paths or command-line flags.
 */
public final class MacOsCaptureHelperSession {

	private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(20);

	private Process helperProcess;
	private Path readyFile;
	private MacOsCaptureArtifacts artifacts;
	private Path helperLogFile;

	public boolean isRunning() {
		return helperProcess != null && helperProcess.isAlive();
	}

	public void ensureBuilt() throws IOException, InterruptedException {
		if (!MacOsCaptureHelperLocator.isSupportedPlatform()) {
			throw new IOException("macOS capture helper requested on non-macOS platform");
		}
		MacOsCaptureHelperLocator.prepareBundledHelperAsync();
		if (MacOsCaptureHelperLocator.isHelperBuilt()) {
			return;
		}
		Path script = MacOsCaptureHelperLocator.getBuildScriptPath();
		if (!Files.isRegularFile(script)) {
			throw new IOException("BlockChat macOS capture helper build script not found: " + script);
		}
		Process process = new ProcessBuilder(script.toString())
			.directory(MacOsCaptureHelperLocator.getHelperDirectory().toFile())
			.inheritIO()
			.start();
		int exitCode = process.waitFor();
		if (exitCode != 0 || !MacOsCaptureHelperLocator.isHelperBuilt()) {
			throw new IOException("Failed to build BlockChat macOS capture helper (exit " + exitCode + ")");
		}
	}

	public void start(Config config) throws IOException, InterruptedException {
		if (isRunning()) {
			return;
		}
		ensureBuilt();

		Files.createDirectories(config.videoOutput().getParent());
		Files.createDirectories(config.systemAudioOutput().getParent());
		if (config.microphoneOutput() != null) {
			Files.createDirectories(config.microphoneOutput().getParent());
		}
		artifacts = new MacOsCaptureArtifacts(
			config.videoOutput(),
			config.systemAudioOutput(),
			config.microphoneOutput(),
			config.helperLogFile()
		);
		helperLogFile = config.helperLogFile() != null
			? config.helperLogFile()
			: config.videoOutput().getParent().resolve("macos-capture-helper.log");

		readyFile = config.readyFile() != null
			? config.readyFile()
			: config.videoOutput().getParent().resolve("blockchat_macos_capture_helper_ready.json");
		Files.deleteIfExists(readyFile);
		Files.createDirectories(helperLogFile.getParent());
		Files.deleteIfExists(helperLogFile);

		List<String> command = new ArrayList<>();
		command.add(MacOsCaptureHelperLocator.getBinaryPath().toString());
		command.add("--video-out");
		command.add(config.videoOutput().toString());
		command.add("--system-audio-out");
		command.add(config.systemAudioOutput().toString());
		command.add("--ready-file");
		command.add(readyFile.toString());
		command.add("--capture-pid");
		command.add(Long.toString(config.capturePid()));
		command.add("--max-video-height");
		command.add(Integer.toString(config.maxVideoHeight()));
		if (config.microphoneOutput() != null) {
			command.add("--microphone-out");
			command.add(config.microphoneOutput().toString());
		}
		if (config.microphoneDeviceId() != null && !config.microphoneDeviceId().isBlank()) {
			command.add("--microphone-device-id");
			command.add(config.microphoneDeviceId());
		}

		helperProcess = new ProcessBuilder(command)
			.directory(MacOsCaptureHelperLocator.getHelperDirectory().toFile())
			.redirectErrorStream(true)
			.redirectOutput(helperLogFile.toFile())
			.start();

		waitForReady(config.readyTimeout() != null ? config.readyTimeout() : DEFAULT_READY_TIMEOUT);
		BlockChatClientMod.LOGGER.info(
			"Started BlockChat macOS capture helper: {}",
			MacOsCaptureHelperLocator.getBinaryPath()
		);
	}

	public void stop() {
		if (helperProcess == null) {
			return;
		}
		Process process = helperProcess;
		helperProcess = null;
		try {
			process.destroy();
			if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	public MacOsCaptureArtifacts getArtifacts() {
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
					"BlockChat macOS capture helper exited before signaling readiness"
						+ summarizeHelperLogSuffix()
				);
			}
			Thread.sleep(100L);
		}
		throw new IOException(
			"Timed out waiting for BlockChat macOS capture helper ready file"
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
		Path systemAudioOutput,
		Path microphoneOutput,
		String microphoneDeviceId,
		long capturePid,
		int maxVideoHeight,
		Path helperLogFile,
		Path readyFile,
		Duration readyTimeout
	) {
	}
}
