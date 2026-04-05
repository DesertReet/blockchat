package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finalizes a recorded clip by copying the already-encoded H.264 video stream
 * and encoding only the mixed audio stream. This keeps post-record processing
 * fast enough to run immediately after the user stops recording.
 */
public final class AudioMuxer {

	private static final long PROCESS_TIMEOUT_SECONDS = 20L;
	private static final String MIXED_AUDIO_LABEL = "blockchat_audio";

	private AudioMuxer() {
	}

	public static void finalizeClip(Path videoPath, List<Path> audioTracks, Path outputPath) throws IOException {
		if (audioTracks != null && !audioTracks.isEmpty()) {
			mux(videoPath, audioTracks, outputPath);
			return;
		}
		copyVideo(videoPath, outputPath);
	}

	public static void mux(Path videoPath, List<Path> audioTracks, Path outputPath) throws IOException {
		if (videoPath == null || !Files.isRegularFile(videoPath)) {
			throw new IOException("Video input missing for BlockChat mux: " + videoPath);
		}
		if (audioTracks == null || audioTracks.isEmpty()) {
			throw new IOException("No audio tracks provided for BlockChat mux");
		}

		String ffmpeg = FFmpegLocator.getPath();
		if (ffmpeg == null) {
			throw new IOException("FFmpeg not available for BlockChat mux");
		}

		Files.createDirectories(outputPath.toAbsolutePath().normalize().getParent());

		List<String> command = new ArrayList<>();
		command.add(ffmpeg);
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("warning");
		command.add("-y");
		command.add("-i");
		command.add(videoPath.toString());
		for (Path audioTrack : audioTracks) {
			command.add("-i");
			command.add(audioTrack.toString());
		}
		command.add("-filter_complex");
		command.add(buildFilterGraph(audioTracks.size()));
		command.add("-map");
		command.add("0:v:0");
		command.add("-map");
		command.add("[" + MIXED_AUDIO_LABEL + "]");
		command.add("-c:v");
		command.add("copy");
		command.add("-c:a");
		command.add("aac");
		command.add("-b:a");
		command.add("192k");
		command.add("-movflags");
		command.add("+faststart");
		command.add("-shortest");
		command.add(outputPath.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();

		StringBuilder output = new StringBuilder();
		Thread drainThread = new Thread(() -> drainOutput(process, output), "BlockChat-AudioMuxer");
		drainThread.setDaemon(true);
		drainThread.start();

		try {
			if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(2L, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				throw new IOException("Timed out while muxing BlockChat clip audio");
			}
			drainThread.join(2000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while muxing BlockChat clip audio", e);
		}

		if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
			throw new IOException("FFmpeg mux failed: " + output);
		}
		BlockChatClientMod.LOGGER.info(
			"BlockChat mux complete: {} audio track(s) -> {}",
			audioTracks.size(),
			outputPath
		);
	}

	/**
	 * Mixes additional audio sidecar tracks into a video file that already contains
	 * an audio stream. The existing audio in the video is mixed with the sidecar
	 * tracks using amix.
	 */
	public static void muxWithExistingAudio(Path videoPath, List<Path> sidecarTracks, Path outputPath) throws IOException {
		if (videoPath == null || !Files.isRegularFile(videoPath)) {
			throw new IOException("Video input missing for BlockChat mux-with-existing: " + videoPath);
		}
		if (sidecarTracks == null || sidecarTracks.isEmpty()) {
			throw new IOException("No sidecar audio tracks for BlockChat mux-with-existing");
		}

		String ffmpeg = FFmpegLocator.getPath();
		if (ffmpeg == null) {
			throw new IOException("FFmpeg not available for BlockChat mux-with-existing");
		}

		Files.createDirectories(outputPath.toAbsolutePath().normalize().getParent());

		// Total audio inputs = 1 (existing audio in video) + sidecar count
		int totalAudioInputs = 1 + sidecarTracks.size();

		// Build filter: [0:a] is existing audio, [1:a], [2:a]... are sidecars
		StringBuilder filter = new StringBuilder();
		filter.append("[0:a]");
		for (int i = 0; i < sidecarTracks.size(); i++) {
			filter.append("[").append(i + 1).append(":a]");
		}
		filter.append("amix=inputs=").append(totalAudioInputs)
			.append(":normalize=0:dropout_transition=0,")
			.append("aresample=async=1:first_pts=0[").append(MIXED_AUDIO_LABEL).append("]");

		List<String> command = new ArrayList<>();
		command.add(ffmpeg);
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("warning");
		command.add("-y");
		command.add("-i");
		command.add(videoPath.toString());
		for (Path sidecar : sidecarTracks) {
			command.add("-i");
			command.add(sidecar.toString());
		}
		command.add("-filter_complex");
		command.add(filter.toString());
		command.add("-map");
		command.add("0:v:0");
		command.add("-map");
		command.add("[" + MIXED_AUDIO_LABEL + "]");
		command.add("-c:v");
		command.add("copy");
		command.add("-c:a");
		command.add("aac");
		command.add("-b:a");
		command.add("192k");
		command.add("-movflags");
		command.add("+faststart");
		command.add("-shortest");
		command.add(outputPath.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();

		StringBuilder output = new StringBuilder();
		Thread drainThread = new Thread(() -> drainOutput(process, output), "BlockChat-AudioMuxExisting");
		drainThread.setDaemon(true);
		drainThread.start();

		try {
			if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(2L, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				throw new IOException("Timed out while muxing BlockChat clip audio (with existing)");
			}
			drainThread.join(2000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while muxing BlockChat clip audio (with existing)", e);
		}

		if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
			throw new IOException("FFmpeg mux-with-existing failed: " + output);
		}
		BlockChatClientMod.LOGGER.info(
			"BlockChat mux-with-existing complete: {} sidecar track(s) + existing audio -> {}",
			sidecarTracks.size(),
			outputPath
		);
	}

	public static void copyVideo(Path videoPath, Path outputPath) throws IOException {
		if (videoPath == null || !Files.isRegularFile(videoPath)) {
			throw new IOException("Video input missing for BlockChat silent clip copy: " + videoPath);
		}
		if (outputPath == null) {
			throw new IOException("Video output missing for BlockChat silent clip copy");
		}
		if (videoPath.equals(outputPath)) {
			return;
		}
		Files.createDirectories(outputPath.toAbsolutePath().normalize().getParent());
		if (requiresVideoRemux(videoPath, outputPath)) {
			remuxVideo(videoPath, outputPath);
			BlockChatClientMod.LOGGER.info("BlockChat silent clip remuxed -> {}", outputPath);
			return;
		}
		try {
			Files.deleteIfExists(outputPath);
			Files.createLink(outputPath, videoPath);
		} catch (IOException | UnsupportedOperationException linkFailure) {
			Files.copy(videoPath, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		BlockChatClientMod.LOGGER.info("BlockChat silent clip copied -> {}", outputPath);
	}

	private static boolean requiresVideoRemux(Path videoPath, Path outputPath) {
		return !fileExtension(videoPath).equals(fileExtension(outputPath));
	}

	private static String fileExtension(Path path) {
		if (path == null || path.getFileName() == null) {
			return "";
		}
		String name = path.getFileName().toString();
		int dotIndex = name.lastIndexOf('.');
		if (dotIndex < 0 || dotIndex == name.length() - 1) {
			return "";
		}
		return name.substring(dotIndex + 1).toLowerCase(java.util.Locale.ROOT);
	}

	private static void remuxVideo(Path videoPath, Path outputPath) throws IOException {
		String ffmpeg = FFmpegLocator.getPath();
		if (ffmpeg == null) {
			throw new IOException("FFmpeg not available for BlockChat silent clip remux");
		}

		List<String> command = new ArrayList<>();
		command.add(ffmpeg);
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("warning");
		command.add("-y");
		command.add("-i");
		command.add(videoPath.toString());
		command.add("-map");
		command.add("0:v:0");
		command.add("-c:v");
		command.add("copy");
		command.add("-an");
		command.add(outputPath.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();

		StringBuilder output = new StringBuilder();
		Thread drainThread = new Thread(() -> drainOutput(process, output), "BlockChat-VideoRemux");
		drainThread.setDaemon(true);
		drainThread.start();

		try {
			if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(2L, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				throw new IOException("Timed out while remuxing BlockChat silent clip");
			}
			drainThread.join(2000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while remuxing BlockChat silent clip", e);
		}

		if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
			throw new IOException("FFmpeg silent clip remux failed: " + output);
		}
	}

	private static String buildFilterGraph(int audioTrackCount) {
		StringBuilder filter = new StringBuilder();
		if (audioTrackCount == 1) {
			filter.append("[1:a]");
		} else {
			for (int i = 0; i < audioTrackCount; i++) {
				filter.append("[").append(i + 1).append(":a]");
			}
			filter.append("amix=inputs=").append(audioTrackCount).append(":normalize=0:dropout_transition=0,");
		}
		filter.append("aresample=async=1:first_pts=0,apad[").append(MIXED_AUDIO_LABEL).append("]");
		return filter.toString();
	}

	private static void drainOutput(Process process, StringBuilder output) {
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
		)) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
				BlockChatClientMod.LOGGER.trace("ffmpeg(mux): {}", line);
			}
		} catch (IOException ignored) {
		}
	}
}
