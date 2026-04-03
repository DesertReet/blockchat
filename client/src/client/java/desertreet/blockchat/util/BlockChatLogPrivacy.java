package desertreet.blockchat.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Redacts paths and device-identifying strings for on-disk diagnostics (e.g. {@code blockchat/last-recording-debug.log})
 * so logs stay useful without embedding home directories, full URLs with tokens, or hardware names.
 */
public final class BlockChatLogPrivacy {

	private static final Pattern UNIQUE_ID = Pattern.compile("uniqueID=[^\\s]+");
	private static final Pattern MODEL_ID_TAIL = Pattern.compile("modelID=.*$");
	private static final Pattern ABS_UNIX_PATH = Pattern.compile("(/Users/|/home/)[^\\s:]+/[^\\s]+");
	private static final Pattern WIN_DRIVE_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s]+");

	private BlockChatLogPrivacy() {
	}

	public static String privacySafePath(Path path) {
		if (path == null) {
			return "null";
		}
		try {
			Path abs = path.toAbsolutePath().normalize();
			Path game = BlockChatPaths.getGameDirectoryPath().toAbsolutePath().normalize();
			if (abs.startsWith(game)) {
				String rel = game.relativize(abs).toString().replace('\\', '/');
				return rel.isEmpty() ? "." : rel;
			}
			String fileName = abs.getFileName() != null ? abs.getFileName().toString() : "unknown";
			return "<outside_game_dir>/" + fileName;
		} catch (Exception e) {
			return "<path_error>";
		}
	}

	public static String privacySafeUrlHost(String url) {
		if (url == null || url.isBlank()) {
			return "null";
		}
		try {
			int scheme = url.indexOf("://");
			if (scheme < 0) {
				return "<url_redacted>";
			}
			int hostStart = scheme + 3;
			int pathOrQuery = url.indexOf('/', hostStart);
			int q = url.indexOf('?', hostStart);
			int end = pathOrQuery < 0 ? url.length() : pathOrQuery;
			if (q >= 0 && q < end) {
				end = q;
			}
			if (hostStart >= end) {
				return "<url_redacted>";
			}
			return url.substring(0, end) + "/…";
		} catch (Exception e) {
			return "<url_redacted>";
		}
	}

	/**
	 * macOS helper logs may list audio input devices with hardware names and persistent IDs.
	 */
	public static String redactMacosHelperLogLine(String line) {
		if (line == null || line.isBlank()) {
			return line;
		}
		String s = UNIQUE_ID.matcher(line).replaceAll("uniqueID=<redacted>");
		if (s.contains("localizedName=")) {
			int nameIdx = s.indexOf("localizedName=");
			int modelIdx = s.indexOf(" modelID=", nameIdx);
			if (modelIdx > nameIdx) {
				s = s.substring(0, nameIdx) + "localizedName=<redacted>" + s.substring(modelIdx);
			} else {
				s = s.substring(0, nameIdx) + "localizedName=<redacted>";
			}
		}
		s = MODEL_ID_TAIL.matcher(s).replaceAll("modelID=<redacted>");
		s = redactEmbeddedPaths(s);
		return s;
	}

	private static String redactEmbeddedPaths(String line) {
		String s = ABS_UNIX_PATH.matcher(line).replaceAll("<path_redacted>");
		s = WIN_DRIVE_PATH.matcher(s).replaceAll("<path_redacted>");
		return s;
	}
}
