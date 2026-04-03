package desertreet.blockchat.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class BlockChatChatState {

	private static final Map<String, RecentContact> RECENTS_BY_UUID = new HashMap<>();
	private static final AtomicLong NEXT_OPTIMISTIC_SEND_ID = new AtomicLong(1L);
	private static final Map<Long, OptimisticSendSnapshot> OPTIMISTIC_SENDS = new HashMap<>();
	private static final BlockChatWebSocket.MessageListener WEBSOCKET_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
			BlockChatWebSocket.requestChatRecents();
		}

		@Override
		public void onDisconnected(String reason) {
		}
	};

	private static boolean listenerInstalled;

	private BlockChatChatState() {
	}

	public static void installWebSocketListener() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(WEBSOCKET_LISTENER);
		listenerInstalled = true;
	}

	public static void clear() {
		RECENTS_BY_UUID.clear();
		OPTIMISTIC_SENDS.clear();
	}

	public static void requestRecentsIfConnected() {
		if (BlockChatWebSocket.isConnected()) {
			BlockChatWebSocket.requestChatRecents();
		}
	}

	public static Map<String, RecentContact> recentsByUuid() {
		return Collections.unmodifiableMap(RECENTS_BY_UUID);
	}

	public static long recordLocalOutgoing(Collection<OutgoingTarget> targets, MediaType mediaType) {
		if (targets == null || targets.isEmpty()) {
			return 0L;
		}
		long timestampMs = System.currentTimeMillis();
		Map<String, OptimisticRecentState> snapshots = new HashMap<>();
		for (OutgoingTarget target : targets) {
			String uuid = BlockChatPlayerUuid.canonicalize(target.uuid());
			if (uuid == null) {
				continue;
			}
			PlayerLookupCache.rememberFromSocial(target.username(), uuid, target.skinUrl());
			RecentContact before = RECENTS_BY_UUID.get(uuid);
			RecentContact after = withLocalOutgoing(before, uuid, target.username(), target.skinUrl(), mediaType, timestampMs);
			RECENTS_BY_UUID.put(uuid, after);
			snapshots.put(uuid, new OptimisticRecentState(before, after));
		}
		if (snapshots.isEmpty()) {
			return 0L;
		}
		long optimisticId = NEXT_OPTIMISTIC_SEND_ID.getAndIncrement();
		OPTIMISTIC_SENDS.put(optimisticId, new OptimisticSendSnapshot(snapshots));
		return optimisticId;
	}

	public static void confirmOptimisticOutgoing(long optimisticSendId) {
		if (optimisticSendId <= 0L) {
			return;
		}
		OPTIMISTIC_SENDS.remove(optimisticSendId);
	}

	public static void rollbackOptimisticOutgoing(long optimisticSendId) {
		if (optimisticSendId <= 0L) {
			return;
		}
		OptimisticSendSnapshot snapshot = OPTIMISTIC_SENDS.remove(optimisticSendId);
		if (snapshot == null) {
			return;
		}
		for (Map.Entry<String, OptimisticRecentState> entry : snapshot.byUuid().entrySet()) {
			String uuid = entry.getKey();
			OptimisticRecentState state = entry.getValue();
			RecentContact current = RECENTS_BY_UUID.get(uuid);
			if (current != state.after()) {
				continue;
			}
			if (state.before() == null) {
				RECENTS_BY_UUID.remove(uuid);
			} else {
				RECENTS_BY_UUID.put(uuid, state.before());
			}
		}
		requestRecentsIfConnected();
	}

	public static void markIncomingViewedFromSender(String senderUuid, long viewedAtMs) {
		String uuid = BlockChatPlayerUuid.canonicalize(senderUuid);
		if (uuid == null) {
			return;
		}
		RecentContact previous = RECENTS_BY_UUID.get(uuid);
		if (previous == null) {
			return;
		}
		int nextIncomingUnopened = Math.max(0, previous.incomingUnopenedCount() - 1);
		RECENTS_BY_UUID.put(
			uuid,
			new RecentContact(
				previous.uuid(),
				previous.username(),
				previous.skinUrl(),
				previous.lastDirection(),
				previous.lastMediaType(),
				previous.lastTimestampMs(),
				nextIncomingUnopened,
				nextIncomingUnopened > 0 ? previous.incomingUnopenedMediaType() : null,
				nextIncomingUnopened > 0 ? previous.incomingUnopenedTimestampMs() : 0L,
				previous.outgoingUnopenedCount(),
				previous.outgoingUnopenedMediaType(),
				previous.outgoingUnopenedTimestampMs(),
				Math.max(previous.latestActivityTimestampMs(), viewedAtMs)
			)
		);
	}

	public static String snapshotString() {
		StringBuilder snapshot = new StringBuilder();
		RECENTS_BY_UUID.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				RecentContact recent = entry.getValue();
				snapshot.append(entry.getKey()).append('\u001f')
					.append(recent.username()).append('\u001f')
					.append(String.valueOf(recent.skinUrl())).append('\u001f')
					.append(recent.lastDirection()).append('\u001f')
					.append(recent.lastMediaType()).append('\u001f')
					.append(recent.lastTimestampMs()).append('\u001f')
					.append(recent.incomingUnopenedCount()).append('\u001f')
					.append(String.valueOf(recent.incomingUnopenedMediaType())).append('\u001f')
					.append(recent.incomingUnopenedTimestampMs()).append('\u001f')
					.append(recent.outgoingUnopenedCount()).append('\u001f')
					.append(String.valueOf(recent.outgoingUnopenedMediaType())).append('\u001f')
					.append(recent.outgoingUnopenedTimestampMs()).append('\u001f')
					.append(recent.latestActivityTimestampMs()).append('\n');
			});
		return snapshot.toString();
	}

	private static void handleMessage(JsonObject message) {
		if (message == null || !message.has("type")) {
			return;
		}

		String type = message.get("type").getAsString();
		switch (type) {
			case "chat_recents" -> handleChatRecents(message);
			case "snap_received" -> handleSnapReceived(message);
			case "snap_opened" -> handleSnapOpened(message);
			default -> {
			}
		}
	}

	private static void handleChatRecents(JsonObject message) {
		RECENTS_BY_UUID.clear();
		OPTIMISTIC_SENDS.clear();
		JsonArray recents = message.getAsJsonArray("recents");
		if (recents == null) {
			return;
		}
		for (JsonElement element : recents) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject recent = element.getAsJsonObject();
			String uuid = BlockChatPlayerUuid.canonicalize(readString(recent, "uuid"));
			if (uuid == null) {
				continue;
			}
			String username = readString(recent, "username");
			String skinUrl = readNullableString(recent, "skin_url");
			Direction lastDirection = "received".equals(readString(recent, "last_direction")) ? Direction.RECEIVED : Direction.SENT;
			MediaType lastMediaType = readMediaType(recent, "last_media_type", MediaType.IMAGE);
			long lastTimestampMs = parseTimestampMs(readString(recent, "last_timestamp"));
			int incomingUnopenedCount = readInt(recent, "incoming_unopened_count");
			if (incomingUnopenedCount == 0 && recent.has("unopened_count")) {
				incomingUnopenedCount = readInt(recent, "unopened_count");
			}
			MediaType incomingUnopenedMediaType = readNullableMediaType(recent, "incoming_unopened_media_type");
			if (incomingUnopenedMediaType == null && incomingUnopenedCount > 0 && lastDirection == Direction.RECEIVED) {
				incomingUnopenedMediaType = lastMediaType;
			}
			long incomingUnopenedTimestampMs = parseOptionalTimestampMs(
				readString(recent, "incoming_unopened_timestamp"),
				incomingUnopenedCount > 0 ? lastTimestampMs : 0L
			);
			int outgoingUnopenedCount = readInt(recent, "outgoing_unopened_count");
			MediaType outgoingUnopenedMediaType = readNullableMediaType(recent, "outgoing_unopened_media_type");
			if (outgoingUnopenedMediaType == null && outgoingUnopenedCount > 0 && lastDirection == Direction.SENT) {
				outgoingUnopenedMediaType = lastMediaType;
			}
			long outgoingUnopenedTimestampMs = parseOptionalTimestampMs(
				readString(recent, "outgoing_unopened_timestamp"),
				outgoingUnopenedCount > 0 ? lastTimestampMs : 0L
			);
			long latestActivityTimestampMs = parseOptionalTimestampMs(
				firstNonBlank(
					readString(recent, "latest_activity_timestamp"),
					readString(recent, "last_activity_timestamp"),
					readString(recent, "activity_timestamp")
				),
				Math.max(
					lastTimestampMs,
					Math.max(incomingUnopenedTimestampMs, outgoingUnopenedTimestampMs)
				)
			);
			PlayerLookupCache.rememberFromSocial(username, uuid, skinUrl);
			RECENTS_BY_UUID.put(
				uuid,
				new RecentContact(
					uuid,
					resolveUsername(uuid, username, null),
					resolveSkinUrl(skinUrl, null),
					lastDirection,
					lastMediaType,
					lastTimestampMs,
					Math.max(0, incomingUnopenedCount),
					incomingUnopenedMediaType,
					incomingUnopenedTimestampMs,
					Math.max(0, outgoingUnopenedCount),
					outgoingUnopenedMediaType,
					outgoingUnopenedTimestampMs,
					latestActivityTimestampMs
				)
			);
		}
	}

	private static void handleSnapReceived(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "from"));
		if (uuid == null) {
			return;
		}
		String username = readString(message, "from_username");
		String mediaType = readString(message, "media_type");
		long timestampMs = parseTimestampMs(readString(message, "sent_at"));
		RECENTS_BY_UUID.put(
			uuid,
			withIncomingReceived(
				RECENTS_BY_UUID.get(uuid),
				uuid,
				username,
				PlayerLookupCache.resolveSkinUrl(username, uuid, null),
				"video".equals(mediaType) ? MediaType.VIDEO : MediaType.IMAGE,
				timestampMs
			)
		);
	}

	private static void handleSnapOpened(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "by"));
		if (uuid == null) {
			return;
		}
		RecentContact previous = RECENTS_BY_UUID.get(uuid);
		if (previous == null) {
			return;
		}
		long openedAtMs = parseOptionalTimestampMs(readString(message, "opened_at"), System.currentTimeMillis());
		int outgoingUnopenedCount = Math.max(0, previous.outgoingUnopenedCount() - 1);
		RECENTS_BY_UUID.put(
			uuid,
			new RecentContact(
				previous.uuid(),
				previous.username(),
				previous.skinUrl(),
				previous.lastDirection(),
				previous.lastMediaType(),
				previous.lastTimestampMs(),
				previous.incomingUnopenedCount(),
				previous.incomingUnopenedMediaType(),
				previous.incomingUnopenedTimestampMs(),
				outgoingUnopenedCount,
				outgoingUnopenedCount > 0 ? previous.outgoingUnopenedMediaType() : null,
				outgoingUnopenedCount > 0 ? previous.outgoingUnopenedTimestampMs() : 0L,
				Math.max(previous.latestActivityTimestampMs(), openedAtMs)
			)
		);
	}

	private static RecentContact withLocalOutgoing(
		RecentContact existing,
		String uuid,
		String username,
		String skinUrl,
		MediaType mediaType,
		long timestampMs
	) {
		Direction lastDirection = existing == null || timestampMs >= existing.lastTimestampMs()
			? Direction.SENT
			: existing.lastDirection();
		MediaType lastMediaType = existing == null || timestampMs >= existing.lastTimestampMs()
			? mediaType
			: existing.lastMediaType();
		long lastTimestampMs = existing == null ? timestampMs : Math.max(existing.lastTimestampMs(), timestampMs);
		// Keep the oldest unopened outgoing block's media type
		MediaType outgoingMediaType = (existing != null && existing.outgoingUnopenedCount() > 0)
			? existing.outgoingUnopenedMediaType()
			: mediaType;
		long outgoingTimestampMs = (existing != null && existing.outgoingUnopenedCount() > 0)
			? existing.outgoingUnopenedTimestampMs()
			: timestampMs;
		return new RecentContact(
			uuid,
			resolveUsername(uuid, username, existing),
			resolveSkinUrl(skinUrl, existing),
			lastDirection,
			lastMediaType,
			lastTimestampMs,
			existing == null ? 0 : existing.incomingUnopenedCount(),
			existing == null ? null : existing.incomingUnopenedMediaType(),
			existing == null ? 0L : existing.incomingUnopenedTimestampMs(),
			(existing == null ? 0 : existing.outgoingUnopenedCount()) + 1,
			outgoingMediaType,
			outgoingTimestampMs,
			existing == null ? timestampMs : Math.max(existing.latestActivityTimestampMs(), timestampMs)
		);
	}

	private static RecentContact withIncomingReceived(
		RecentContact existing,
		String uuid,
		String username,
		String skinUrl,
		MediaType mediaType,
		long timestampMs
	) {
		Direction lastDirection = existing == null || timestampMs >= existing.lastTimestampMs() ? Direction.RECEIVED : existing.lastDirection();
		MediaType lastMediaType = existing == null || timestampMs >= existing.lastTimestampMs() ? mediaType : existing.lastMediaType();
		long lastTimestampMs = existing == null || timestampMs >= existing.lastTimestampMs() ? timestampMs : existing.lastTimestampMs();
		// Keep the oldest unread block's media type; only use the new one if no prior unreads exist
		MediaType incomingMediaType = (existing != null && existing.incomingUnopenedCount() > 0)
			? existing.incomingUnopenedMediaType()
			: mediaType;
		long incomingTimestampMs = (existing != null && existing.incomingUnopenedCount() > 0)
			? existing.incomingUnopenedTimestampMs()
			: timestampMs;
		return new RecentContact(
			uuid,
			resolveUsername(uuid, username, existing),
			resolveSkinUrl(skinUrl, existing),
			lastDirection,
			lastMediaType,
			lastTimestampMs,
			(existing == null ? 0 : existing.incomingUnopenedCount()) + 1,
			incomingMediaType,
			incomingTimestampMs,
			existing == null ? 0 : existing.outgoingUnopenedCount(),
			existing == null ? null : existing.outgoingUnopenedMediaType(),
			existing == null ? 0L : existing.outgoingUnopenedTimestampMs(),
			existing == null ? timestampMs : Math.max(existing.latestActivityTimestampMs(), timestampMs)
		);
	}

	private static String resolveUsername(String uuid, String username, RecentContact existing) {
		if (username != null && !username.isBlank()) {
			return username;
		}
		return existing == null ? uuid : existing.username();
	}

	private static String resolveSkinUrl(String skinUrl, RecentContact existing) {
		if (skinUrl != null && !skinUrl.isBlank()) {
			return skinUrl;
		}
		return existing == null ? null : existing.skinUrl();
	}

	private static long parseTimestampMs(String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return System.currentTimeMillis();
		}
		try {
			return Instant.parse(timestamp).toEpochMilli();
		} catch (Exception ignored) {
			return System.currentTimeMillis();
		}
	}

	private static long parseOptionalTimestampMs(String timestamp, long fallbackMs) {
		if (timestamp == null || timestamp.isBlank()) {
			return fallbackMs;
		}
		return parseTimestampMs(timestamp);
	}

	private static int readInt(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return 0;
		}
		try {
			return object.get(key).getAsInt();
		} catch (Exception ignored) {
			return 0;
		}
	}

	private static String readString(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		return object.get(key).getAsString();
	}

	private static String readNullableString(JsonObject object, String key) {
		String value = readString(object, key);
		return value == null || value.isBlank() ? null : value;
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static MediaType readMediaType(JsonObject object, String key, MediaType fallback) {
		MediaType mediaType = readNullableMediaType(object, key);
		return mediaType != null ? mediaType : fallback;
	}

	private static MediaType readNullableMediaType(JsonObject object, String key) {
		String value = readString(object, key);
		if (value == null || value.isBlank()) {
			return null;
		}
		return "video".equals(value) ? MediaType.VIDEO : MediaType.IMAGE;
	}

	public enum Direction {
		SENT,
		RECEIVED
	}

	public enum MediaType {
		IMAGE,
		VIDEO
	}

	public record OutgoingTarget(String uuid, String username, String skinUrl) {
	}

	private record OptimisticRecentState(RecentContact before, RecentContact after) {
	}

	private record OptimisticSendSnapshot(Map<String, OptimisticRecentState> byUuid) {
	}

	public record RecentContact(
		String uuid,
		String username,
		String skinUrl,
		Direction lastDirection,
		MediaType lastMediaType,
		long lastTimestampMs,
		int incomingUnopenedCount,
		MediaType incomingUnopenedMediaType,
		long incomingUnopenedTimestampMs,
		int outgoingUnopenedCount,
		MediaType outgoingUnopenedMediaType,
		long outgoingUnopenedTimestampMs,
		long latestActivityTimestampMs
	) {
		public boolean hasIncomingUnread() {
			return incomingUnopenedCount > 0;
		}

		public boolean hasOutgoingUnopened() {
			return outgoingUnopenedCount > 0;
		}
	}
}
