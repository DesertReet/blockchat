package desertreet.blockchat.capture.macos;

import java.nio.file.Path;

public record MacOsCaptureArtifacts(
	Path videoPath,
	Path systemAudioPath,
	Path microphonePath,
	Path helperLogPath
) {
}
