package desertreet.blockchat.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves player usernames to UUIDs and skin URLs using the existing
 * search_user WebSocket message. Batches requests (max 30 at a time)
 * and backs off for 1 minute if rate-limited.
 */
public final class PlayerLookupCache {

	private static final int BATCH_SIZE = 30;
	private static final long RATE_LIMIT_BACKOFF_MS = 60_000;
	private static final long RETRY_INTERVAL_TICKS = 20; // 1 second between batches

	/** Resolved results: username (lowercase) -> LookupResult */
	private static final Map<String, LookupResult> RESOLVED = new ConcurrentHashMap<>();

	/** Usernames currently awaiting a response */
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

	/** Queue of usernames still needing lookup */
	private static final Deque<String> QUEUE = new ArrayDeque<>();

	/** Tick of last rate-limit hit */
	private static long rateLimitedUntil;

	/** Tick counter for batch pacing */
	private static int tickCounter;

	private static boolean listenerInstalled;

	private static final BlockChatWebSocket.MessageListener LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
			// Re-queue any pending lookups that may have been lost on disconnect
			PENDING.clear();
		}

		@Override
		public void onDisconnected(String reason) {
			PENDING.clear();
		}
	};

	private PlayerLookupCache() {}

	/**
	 * Merges authoritative friend/profile data into the username-keyed cache so
	 * {@link #resolveSkinUrl} and {@link #lookup} can reuse UUID + skin URL without a redundant search.
	 */
	public static void rememberFromSocial(String username, String uuid, String skinUrl) {
		if (username == null || username.isBlank()) {
			return;
		}
		ensureListener();
		String key = username.toLowerCase(Locale.ROOT);
		String u = BlockChatPlayerUuid.canonicalize(uuid);
		LookupResult prev = RESOLVED.get(key);
		String su = skinUrl != null && !skinUrl.isBlank() ? skinUrl.trim() : null;
		if (su == null && prev != null) {
			su = prev.skinUrl();
		}
		String uid = u != null ? u : (prev != null && prev.isFound() ? prev.uuid() : null);
		if (uid == null) {
			return;
		}
		String disp = username.trim();
		if (prev != null && prev.username() != null && !prev.username().isBlank()) {
			disp = prev.username();
		}
		RESOLVED.put(key, new LookupResult(uid, disp, su));
	}

	/**
	 * Skin URL for rendering: prefers {@code skinUrlHint} (e.g. from websocket), then cached lookup / prior
	 * {@link #rememberFromSocial} data, then triggers {@link #lookup} if needed.
	 */
	public static String resolveSkinUrl(String username, String uuid, String skinUrlHint) {
		if (username != null && !username.isBlank()) {
			rememberFromSocial(username, uuid, skinUrlHint);
		}
		if (skinUrlHint != null && !skinUrlHint.isBlank()) {
			return skinUrlHint.trim();
		}
		if (username == null || username.isBlank()) {
			return null;
		}
		String key = username.toLowerCase(Locale.ROOT);
		LookupResult r = RESOLVED.get(key);
		if (r != null && r.isFound() && r.skinUrl() != null && !r.skinUrl().isBlank()) {
			return r.skinUrl();
		}
		LookupResult q = lookup(username);
		if (q != null && q.isFound() && q.skinUrl() != null && !q.skinUrl().isBlank()) {
			return q.skinUrl();
		}
		return null;
	}

	/**
	 * Cached row for {@code username} if present (resolved or not-found), without queueing a new search.
	 */
	public static LookupResult peekResolved(String username) {
		if (username == null || username.isBlank()) {
			return null;
		}
		return RESOLVED.get(username.toLowerCase(Locale.ROOT));
	}

	/**
	 * Request a lookup for the given username. Returns the cached result
	 * if already resolved, or null if still pending/queued.
	 */
	public static LookupResult lookup(String username) {
		if (username == null || username.isBlank()) return null;

		ensureListener();

		String key = username.toLowerCase();
		LookupResult cached = RESOLVED.get(key);
		if (cached != null) return cached;

		// Queue if not already pending or queued
		if (!PENDING.contains(key)) {
			synchronized (QUEUE) {
				// Check it's not already in queue
				if (!QUEUE.contains(key)) {
					QUEUE.addLast(key);
				}
			}
		}
		return null;
	}

	/**
	 * Call from client tick to process the queue.
	 */
	public static void tick() {
		tickCounter++;
		if (tickCounter % RETRY_INTERVAL_TICKS != 0) return;
		if (!BlockChatWebSocket.isConnected()) return;

		long now = System.currentTimeMillis();
		if (now < rateLimitedUntil) return;

		// Send a batch
		int sent = 0;
		synchronized (QUEUE) {
			while (sent < BATCH_SIZE && !QUEUE.isEmpty()) {
				String username = QUEUE.pollFirst();
				if (username == null) break;
				if (RESOLVED.containsKey(username)) continue;
				if (PENDING.contains(username)) continue;

				PENDING.add(username);
				BlockChatWebSocket.searchUser(username);
				sent++;
			}
		}
	}

	private static void handleMessage(JsonObject message) {
		if (message == null || !message.has("type")) return;
		String type = message.get("type").getAsString();

		if ("user_search_result".equals(type)) {
			handleSearchResult(message);
		} else if ("error".equals(type)) {
			handleError(message);
		}
	}

	private static void handleSearchResult(JsonObject message) {
		String query = readString(message, "query");
		if (query == null) return;

		String key = query.toLowerCase();
		PENDING.remove(key);

		JsonElement userElement = message.get("user");
		if (userElement == null || userElement.isJsonNull()) {
			// Not found - cache as empty so we don't retry
			RESOLVED.put(key, LookupResult.NOT_FOUND);
			return;
		}

		JsonObject user = userElement.getAsJsonObject();
		String uuid = BlockChatPlayerUuid.canonicalize(readString(user, "uuid"));
		String username = readString(user, "username");
		String skinUrl = readNullableString(user, "skin_url");

		RESOLVED.put(key, new LookupResult(uuid, username, skinUrl));
	}

	private static void handleError(JsonObject message) {
		String code = readString(message, "code");
		if (code == null) return;

		if ("rate_limited".equals(code) || "too_many_requests".equals(code)) {
			BlockChatClientMod.LOGGER.warn("PlayerLookupCache: rate limited, backing off for 1 minute");
			rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;

			// Re-queue all pending
			synchronized (QUEUE) {
				for (String pending : PENDING) {
					QUEUE.addFirst(pending);
				}
			}
			PENDING.clear();
			return;
		}

		// For search-related errors, check if we can match a pending query
		if ("user_not_found".equals(code) || "invalid_username".equals(code) || code.contains("search")) {
			String errorQuery = readString(message, "query");
			if (errorQuery != null) {
				String key = errorQuery.toLowerCase();
				PENDING.remove(key);
				RESOLVED.put(key, LookupResult.NOT_FOUND);
			}
		}
	}

	private static void ensureListener() {
		if (listenerInstalled) return;
		BlockChatWebSocket.addMessageListener(LISTENER);
		listenerInstalled = true;
	}

	private static String readString(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
		return obj.get(key).getAsString();
	}

	private static String readNullableString(JsonObject obj, String key) {
		String val = readString(obj, key);
		return val == null || val.isBlank() ? null : val;
	}

	public record LookupResult(String uuid, String username, String skinUrl) {
		public static final LookupResult NOT_FOUND = new LookupResult(null, null, null);

		public boolean isFound() {
			return uuid != null;
		}
	}
}
