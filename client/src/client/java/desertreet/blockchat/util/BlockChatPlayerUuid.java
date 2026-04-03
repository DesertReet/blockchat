package desertreet.blockchat.util;

import java.util.Locale;

/**
 * Normalizes Minecraft player UUID strings for BlockChat identity keys, websocket payloads, and caches.
 * Always uses dashless lowercase hex so dashed server/API forms and {@link java.util.UUID#toString()} match one key.
 */
public final class BlockChatPlayerUuid {

	private BlockChatPlayerUuid() {
	}

	/**
	 * @return dashless lowercase UUID string, or {@code null} if missing or blank after trim
	 */
	public static String canonicalize(String uuid) {
		if (uuid == null) {
			return null;
		}
		String normalized = uuid.trim();
		if (normalized.isEmpty()) {
			return null;
		}
		return normalized.replace("-", "").toLowerCase(Locale.ROOT);
	}
}
