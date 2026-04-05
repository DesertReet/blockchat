package desertreet.blockchat.capture.windows;

import java.nio.file.Path;

public record WindowsNativeCaptureArtifacts(
	Path videoPath,
	Path helperLogPath
) {
}
