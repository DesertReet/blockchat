package desertreet.blockchat.preferences;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import desertreet.blockchat.net.BlockChatWebSocket;

public final class BlockChatPreferenceState {
	private static final long DEFAULT_EXPIRY_MS = 86_400_000L;
	private static final boolean DEFAULT_NOTIFICATIONS_ENABLED = true;
	private static final boolean DEFAULT_FRIEND_REQUESTS_ENABLED = true;

	private static final long[] EXPIRY_VALUES_MS = {
		0L, 3_600_000L, 86_400_000L, 259_200_000L, 604_800_000L
	};

	private static final BlockChatWebSocket.MessageListener WEBSOCKET_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
			// Preferences are requested directly when the websocket opens.
		}

		@Override
		public void onDisconnected(String reason) {
			loaded = false;
		}
	};

	private static boolean listenerInstalled;
	private static boolean loaded;
	private static long defaultExpiryMs = DEFAULT_EXPIRY_MS;
	private static long imageExpiryMs = DEFAULT_EXPIRY_MS;
	private static boolean notificationsEnabled = DEFAULT_NOTIFICATIONS_ENABLED;
	private static boolean friendRequestsEnabled = DEFAULT_FRIEND_REQUESTS_ENABLED;

	private BlockChatPreferenceState() {
	}

	public static void installWebSocketListener() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(WEBSOCKET_LISTENER);
		listenerInstalled = true;
	}

	public static void clear() {
		loaded = false;
		defaultExpiryMs = DEFAULT_EXPIRY_MS;
		imageExpiryMs = DEFAULT_EXPIRY_MS;
		notificationsEnabled = DEFAULT_NOTIFICATIONS_ENABLED;
		friendRequestsEnabled = DEFAULT_FRIEND_REQUESTS_ENABLED;
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static long getDefaultExpiryMs() {
		return defaultExpiryMs;
	}

	public static long getImageExpiryMs() {
		return imageExpiryMs;
	}

	public static boolean isNotificationsEnabled() {
		return notificationsEnabled;
	}

	public static boolean isFriendRequestsEnabled() {
		return friendRequestsEnabled;
	}

	public static void setDefaultExpiryMs(long expiryMs) {
		long normalized = normalizeExpiry(expiryMs);
		defaultExpiryMs = normalized;
		sendPreference("default_expiry_ms", normalized);
	}

	public static void setImageExpiryMs(long expiryMs) {
		long normalized = normalizeExpiry(expiryMs);
		imageExpiryMs = normalized;
		sendPreference("image_expiry_ms", normalized);
	}

	public static void setNotificationsEnabled(boolean enabled) {
		notificationsEnabled = enabled;
		sendPreference("notifications", enabled ? 1 : 0);
	}

	public static void setFriendRequestsEnabled(boolean enabled) {
		friendRequestsEnabled = enabled;
		sendPreference("friend_requests", enabled ? 1 : 0);
	}

	private static void handleMessage(JsonObject message) {
		if (message == null || !message.has("type")) {
			return;
		}

		String type = message.get("type").getAsString();
		switch (type) {
			case "preferences" -> handlePreferences(message);
			case "preference_set" -> handlePreferenceSet(message);
			default -> {
			}
		}
	}

	private static void handlePreferences(JsonObject message) {
		JsonObject preferences = message.has("preferences") && message.get("preferences").isJsonObject()
			? message.getAsJsonObject("preferences")
			: null;
		if (preferences == null) {
			return;
		}
		loaded = true;

		for (var entry : preferences.entrySet()) {
			JsonElement value = entry.getValue();
			if (value == null || value.isJsonNull()) {
				continue;
			}
			applyPreference(entry.getKey(), value.getAsInt());
		}
	}

	private static void handlePreferenceSet(JsonObject message) {
		if (!message.has("key") || !message.has("value") || message.get("value").isJsonNull()) {
			return;
		}
		applyPreference(message.get("key").getAsString(), message.get("value").getAsInt());
	}

	private static void applyPreference(String key, int value) {
		switch (key) {
			case "default_expiry_ms" -> {
				long expiry = value;
				if (isAllowedExpiry(expiry)) {
					defaultExpiryMs = expiry;
				}
			}
			case "image_expiry_ms" -> {
				long expiry = value;
				if (isAllowedExpiry(expiry)) {
					imageExpiryMs = expiry;
				}
			}
			case "notifications" -> notificationsEnabled = value != 0;
			case "friend_requests" -> friendRequestsEnabled = value != 0;
			default -> {
			}
		}
	}

	private static void sendPreference(String key, long value) {
		if (BlockChatWebSocket.isConnected()) {
			BlockChatWebSocket.setPreference(key, Math.toIntExact(value));
		}
	}

	private static void sendPreference(String key, int value) {
		if (BlockChatWebSocket.isConnected()) {
			BlockChatWebSocket.setPreference(key, value);
		}
	}

	private static boolean isAllowedExpiry(long value) {
		for (long allowed : EXPIRY_VALUES_MS) {
			if (allowed == value) {
				return true;
			}
		}
		return false;
	}

	private static long normalizeExpiry(long expiryMs) {
		return isAllowedExpiry(expiryMs) ? expiryMs : DEFAULT_EXPIRY_MS;
	}
}
