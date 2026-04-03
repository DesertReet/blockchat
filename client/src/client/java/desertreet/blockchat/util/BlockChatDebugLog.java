package desertreet.blockchat.util;

import desertreet.blockchat.BlockChatClientMod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only client log for operational and error diagnostics ({@code blockchat/debug.log} under the game directory).
 * Not gated on test mode — failures (upload, download, auth, network) should always be recorded here.
 */
public final class BlockChatDebugLog {

	private static final String FILE_NAME = "debug.log";
	private static final int MAX_MESSAGE_LEN = 2000;
	private static final int MAX_STACK_LEN = 8000;
	private static final ReentrantLock LOCK = new ReentrantLock();

	private BlockChatDebugLog() {
	}

	public static void line(String category, String message) {
		append(Instant.now(), category, message, null);
	}

	public static void line(String category, String message, Throwable error) {
		append(Instant.now(), category, message, error);
	}

	private static void append(Instant time, String category, String message, Throwable error) {
		String safeCategory = sanitizeToken(category);
		String body = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
		if (body.length() > MAX_MESSAGE_LEN) {
			body = body.substring(0, MAX_MESSAGE_LEN - 3) + "...";
		}
		StringBuilder line = new StringBuilder(64 + body.length());
		line.append(time.toString()).append(' ').append(safeCategory).append(' ').append(body);
		if (error != null) {
			line.append(" | ");
			line.append(error.getClass().getSimpleName()).append(": ");
			String em = error.getMessage();
			if (em != null) {
				String sm = em.replace('\n', ' ');
				if (sm.length() > 400) {
					sm = sm.substring(0, 397) + "...";
				}
				line.append(sm);
			}
			StringWriter sw = new StringWriter();
			error.printStackTrace(new PrintWriter(sw));
			String stack = sw.toString();
			if (stack.length() > MAX_STACK_LEN) {
				stack = stack.substring(0, MAX_STACK_LEN - 3) + "...";
			}
			line.append(" | ").append(stack.replace("\n", " | "));
		}

		LOCK.lock();
		try {
			Path root = BlockChatPaths.getClientLogDirectory();
			try {
				BlockChatPaths.ensureBlockChatDirectory(root);
			} catch (IOException dirEx) {
				BlockChatClientMod.LOGGER.warn("BlockChat debug.log directory failed", dirEx);
				return;
			}
			Path logFile = root.resolve(FILE_NAME);
			try (BufferedWriter w = Files.newBufferedWriter(
				logFile,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			)) {
				w.write(line.toString());
				w.newLine();
			}
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("BlockChat debug.log write failed", e);
		} finally {
			LOCK.unlock();
		}
	}

	private static String sanitizeToken(String category) {
		if (category == null || category.isBlank()) {
			return "general";
		}
		String t = category.replace('\n', ' ').replace('\r', ' ').trim();
		if (t.length() > 48) {
			return t.substring(0, 45) + "...";
		}
		return t;
	}
}
