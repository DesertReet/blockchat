package desertreet.blockchat.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.social.BlockChatSocialState;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatLogPrivacy;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class BlockChatInboxState {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private static final Object STATE_LOCK = new Object();
	private static final Map<String, InboxSnap> UNREAD_BY_SNAP_ID = new HashMap<>();
	private static PendingViewRequest pendingViewRequest;
	private static boolean listenerInstalled;

	/** Prefetched media: snapId -> downloaded file path. */
	private static final Map<String, Path> PREFETCHED_MEDIA = new HashMap<>();
	/** Snap IDs currently being prefetched (view_snap sent, awaiting download_url or download). */
	private static final Set<String> PREFETCH_IN_PROGRESS = new HashSet<>();

	private static final BlockChatWebSocket.MessageListener WEBSOCKET_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
			BlockChatWebSocket.requestSnaps();
		}

		@Override
		public void onDisconnected(String reason) {
			clearPendingView("Disconnected");
			clearAllPrefetched();
		}
	};

	private BlockChatInboxState() {
	}

	public static void installWebSocketListener() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(WEBSOCKET_LISTENER);
		listenerInstalled = true;
	}

	public static void clear() {
		clearPendingView("Cleared");
		clearAllPrefetched();
		synchronized (STATE_LOCK) {
			UNREAD_BY_SNAP_ID.clear();
		}
	}

	public static void requestSnapsIfConnected() {
		if (BlockChatWebSocket.isConnected()) {
			BlockChatWebSocket.requestSnaps();
		}
	}

	/**
	 * Re-evaluates unread snaps and starts prefetch for any that are now eligible.
	 * Call this again after social state has hydrated so friend snaps can begin prefetching late.
	 */
	public static void refreshEligibleUnreadPrefetches() {
		List<InboxSnap> eligibleToPrefetch = new ArrayList<>();
		synchronized (STATE_LOCK) {
			pruneIneligiblePrefetchedMediaLocked();
			for (InboxSnap snap : UNREAD_BY_SNAP_ID.values()) {
				if (!shouldPrefetchSnap(snap)) {
					continue;
				}
				if (PREFETCHED_MEDIA.containsKey(snap.snapId()) || PREFETCH_IN_PROGRESS.contains(snap.snapId())) {
					continue;
				}
				eligibleToPrefetch.add(snap);
			}
		}

		for (InboxSnap snap : eligibleToPrefetch) {
			startPrefetch(snap);
		}
	}

	/**
	 * Returns the media type of the oldest unread snap from the given sender, or null if none exist.
	 */
	public static BlockChatChatState.MediaType oldestUnreadMediaTypeForSender(String senderUuid) {
		String canonical = BlockChatPlayerUuid.canonicalize(senderUuid);
		if (canonical == null) {
			return null;
		}
		synchronized (STATE_LOCK) {
			InboxSnap oldest = oldestUnreadForSenderLocked(canonical);
			return oldest == null ? null : oldest.mediaType();
		}
	}

	public static CompletableFuture<ViewedSnap> openOldestUnreadForSender(String senderUuid) {
		String canonicalSenderUuid = BlockChatPlayerUuid.canonicalize(senderUuid);
		if (canonicalSenderUuid == null || !BlockChatWebSocket.isConnected()) {
			return CompletableFuture.completedFuture(null);
		}

		InboxSnap oldestUnread;
		CompletableFuture<ViewedSnap> result = new CompletableFuture<>();
		synchronized (STATE_LOCK) {
			if (pendingViewRequest != null) {
				result.completeExceptionally(new IllegalStateException(BlockChatStrings.BLOCK_VIEW_ALREADY_LOADING));
				return result;
			}
			oldestUnread = oldestUnreadForSenderLocked(canonicalSenderUuid);
			if (oldestUnread == null) {
				result.complete(null);
				return result;
			}

			// Check if media is already prefetched
			Path prefetched = PREFETCHED_MEDIA.remove(oldestUnread.snapId());
			PREFETCH_IN_PROGRESS.remove(oldestUnread.snapId());
			if (prefetched != null && Files.isRegularFile(prefetched) && shouldPrefetchSnap(oldestUnread)) {
				UNREAD_BY_SNAP_ID.remove(oldestUnread.snapId());
				long viewedAtMs = System.currentTimeMillis();
				BlockChatWebSocket.snapViewed(oldestUnread.snapId());
				BlockChatChatState.markIncomingViewedFromSender(oldestUnread.fromUuid(), viewedAtMs);
				result.complete(new ViewedSnap(oldestUnread, prefetched));
				return result;
			} else if (prefetched != null) {
				deleteQuietly(prefetched);
			}

			pendingViewRequest = new PendingViewRequest(oldestUnread, result);
		}

		BlockChatWebSocket.viewSnap(oldestUnread.snapId());
		return result;
	}

	private static InboxSnap oldestUnreadForSenderLocked(String senderUuid) {
		return UNREAD_BY_SNAP_ID.values().stream()
			.filter(snap -> senderUuid.equals(snap.fromUuid()))
			.sorted(Comparator
				.comparingLong(InboxSnap::sentAtMs)
				.thenComparing(InboxSnap::snapId))
			.findFirst()
			.orElse(null);
	}

	private static void handleMessage(JsonObject message) {
		if (message == null || !message.has("type")) {
			return;
		}
		String type = message.get("type").getAsString();
		switch (type) {
			case "snap_list" -> handleSnapList(message);
			case "snap_received" -> handleSnapReceived(message);
			case "snap_download_url" -> handleSnapDownloadUrl(message);
			case "error" -> handleError(message);
			default -> {
			}
		}
	}

	private static void handleSnapList(JsonObject message) {
		JsonArray snaps = message.has("snaps") && message.get("snaps").isJsonArray()
			? message.getAsJsonArray("snaps")
			: null;
		if (snaps == null) {
			return;
		}

		Map<String, InboxSnap> parsed = new HashMap<>();
		for (JsonElement element : snaps) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject snapObject = element.getAsJsonObject();
			if (snapObject.has("viewed_at") && !snapObject.get("viewed_at").isJsonNull()) {
				continue;
			}
			InboxSnap snap = parseInboxSnap(snapObject, null);
			if (snap == null) {
				continue;
			}
			parsed.put(snap.snapId(), snap);
			PlayerLookupCache.rememberFromSocial(snap.fromUsername(), snap.fromUuid(), snap.fromSkinUrl());
		}

		// Clean up prefetched media for snaps no longer in the list
		synchronized (STATE_LOCK) {
			List<String> staleIds = new ArrayList<>();
			for (String id : PREFETCHED_MEDIA.keySet()) {
				if (!parsed.containsKey(id)) {
					staleIds.add(id);
				}
			}
			for (String id : staleIds) {
				Path path = PREFETCHED_MEDIA.remove(id);
				PREFETCH_IN_PROGRESS.remove(id);
				deleteQuietly(path);
			}

			UNREAD_BY_SNAP_ID.clear();
			UNREAD_BY_SNAP_ID.putAll(parsed);
		}

		refreshEligibleUnreadPrefetches();
	}

	private static void handleSnapReceived(JsonObject message) {
		InboxSnap snap = parseInboxSnap(message, null);
		if (snap == null) {
			return;
		}
		PlayerLookupCache.rememberFromSocial(snap.fromUsername(), snap.fromUuid(), snap.fromSkinUrl());
		synchronized (STATE_LOCK) {
			UNREAD_BY_SNAP_ID.put(snap.snapId(), snap);
		}
		refreshEligibleUnreadPrefetches();
	}

	private static void startPrefetch(InboxSnap snap) {
		if (!shouldPrefetchSnap(snap)) {
			return;
		}
		synchronized (STATE_LOCK) {
			if (PREFETCHED_MEDIA.containsKey(snap.snapId()) || PREFETCH_IN_PROGRESS.contains(snap.snapId())) {
				return;
			}
			PREFETCH_IN_PROGRESS.add(snap.snapId());
		}
		BlockChatWebSocket.viewSnap(snap.snapId());
	}

	private static void handleSnapDownloadUrl(JsonObject message) {
		String snapId = readString(message, "snap_id");
		String downloadUrl = readString(message, "download_url");
		if (snapId == null || downloadUrl == null) {
			return;
		}

		// Check if this is for a user-initiated view request
		boolean isUserView;
		InboxSnap mergedSnap;
		synchronized (STATE_LOCK) {
			PendingViewRequest pending = pendingViewRequest;
			isUserView = pending != null && Objects.equals(pending.snap().snapId(), snapId);
			if (isUserView) {
				InboxSnap fromInbox = UNREAD_BY_SNAP_ID.get(snapId);
				mergedSnap = parseInboxSnap(message, fromInbox != null ? fromInbox : pending.snap());
				if (mergedSnap == null) {
					mergedSnap = pending.snap();
				}
			} else {
				// This is a prefetch response - check that it's expected
				if (!PREFETCH_IN_PROGRESS.contains(snapId)) {
					return;
				}
				InboxSnap fromInbox = UNREAD_BY_SNAP_ID.get(snapId);
				mergedSnap = parseInboxSnap(message, fromInbox);
				if (mergedSnap == null && fromInbox != null) {
					mergedSnap = fromInbox;
				}
				if (mergedSnap == null) {
					PREFETCH_IN_PROGRESS.remove(snapId);
					return;
				}
			}
		}

		final InboxSnap finalMergedSnap = mergedSnap;
		final boolean finalIsUserView = isUserView;

		Path downloadPath;
		try {
			downloadPath = Files.createTempFile("blockchat-inbox-", fileExtensionFor(finalMergedSnap));
		} catch (IOException e) {
			BlockChatDebugLog.line("inbox", "temp_download_file_failed snap_id=" + snapId, e);
			if (finalIsUserView) {
				failPendingView(BlockChatStrings.FAILED_PREPARE_MEDIA_PREVIEW, e);
			} else {
				synchronized (STATE_LOCK) {
					PREFETCH_IN_PROGRESS.remove(snapId);
				}
			}
			return;
		}

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(downloadUrl))
			.timeout(Duration.ofMinutes(2))
			.GET()
			.build();

		HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofFile(downloadPath))
			.whenComplete((response, throwable) ->
				Minecraft.getInstance().execute(() -> {
					if (throwable != null) {
						deleteQuietly(downloadPath);
						BlockChatDebugLog.line(
							"inbox",
							"download_failed snap_id=" + snapId
								+ " user_view=" + finalIsUserView
								+ " url=" + BlockChatLogPrivacy.privacySafeUrlHost(downloadUrl),
							throwable
						);
						if (finalIsUserView) {
							failPendingView(BlockChatStrings.FAILED_DOWNLOAD_BLOCK_MEDIA, throwable);
						} else {
							synchronized (STATE_LOCK) {
								PREFETCH_IN_PROGRESS.remove(snapId);
							}
						}
						return;
					}
					if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
						deleteQuietly(downloadPath);
						int status = response != null ? response.statusCode() : -1;
						BlockChatDebugLog.line(
							"inbox",
							"download_http_error snap_id=" + snapId
								+ " user_view=" + finalIsUserView
								+ " status=" + status
								+ " url=" + BlockChatLogPrivacy.privacySafeUrlHost(downloadUrl)
						);
						if (finalIsUserView) {
							failPendingView(BlockChatStrings.FAILED_DOWNLOAD_BLOCK_MEDIA, null);
						} else {
							synchronized (STATE_LOCK) {
								PREFETCH_IN_PROGRESS.remove(snapId);
							}
						}
						return;
					}
					if (finalIsUserView) {
						finishPendingView(finalMergedSnap, downloadPath);
					} else {
						finishPrefetch(finalMergedSnap, downloadPath);
					}
				})
			);
	}

	private static void finishPrefetch(InboxSnap snap, Path downloadedPath) {
		String snapId = snap == null ? null : snap.snapId();
		synchronized (STATE_LOCK) {
			if (snapId == null) {
				deleteQuietly(downloadedPath);
				return;
			}
			PREFETCH_IN_PROGRESS.remove(snapId);
			InboxSnap current = UNREAD_BY_SNAP_ID.get(snapId);
			if (current == null || !shouldPrefetchSnap(current)) {
				// Snap was removed (viewed or cleared) while prefetching
				deleteQuietly(downloadedPath);
				return;
			}
			PREFETCHED_MEDIA.put(snapId, downloadedPath);
		}
	}

	private static void clearAllPrefetched() {
		synchronized (STATE_LOCK) {
			for (Path path : PREFETCHED_MEDIA.values()) {
				deleteQuietly(path);
			}
			PREFETCHED_MEDIA.clear();
			PREFETCH_IN_PROGRESS.clear();
		}
	}

	private static void handleError(JsonObject message) {
		String code = readString(message, "code");
		if (code == null) {
			return;
		}
		if (!"snap_not_found".equals(code)
			&& !"delivery_failed".equals(code)
			&& !"invalid_upload".equals(code)
			&& !"snap_expired".equals(code)) {
			return;
		}

		String refId = readString(message, "ref_id");

		// Check if this error is for a prefetch
		synchronized (STATE_LOCK) {
			if (refId != null && PREFETCH_IN_PROGRESS.contains(refId)) {
				PREFETCH_IN_PROGRESS.remove(refId);
				if ("snap_expired".equals(code)) {
					UNREAD_BY_SNAP_ID.remove(refId);
				}
				// Don't return yet - also check pending view below
			}
		}

		PendingViewRequest pending;
		String pendingSnapId;
		synchronized (STATE_LOCK) {
			pending = pendingViewRequest;
			pendingSnapId = pending == null ? null : pending.snap().snapId();
			if ("snap_expired".equals(code) && pendingSnapId != null) {
				UNREAD_BY_SNAP_ID.remove(pendingSnapId);
			}
		}
		if (pending == null) {
			return;
		}
		if (refId != null && !refId.equals(pendingSnapId)) {
			return;
		}
		BlockChatDebugLog.line(
			"inbox",
			"server_inbox_error code=" + code + " ref_id=" + (refId != null ? refId : "")
		);
		failPendingView(BlockChatStrings.COULD_NOT_OPEN_BLOCK, null);
		if ("snap_expired".equals(code)) {
			BlockChatWebSocket.requestSnaps();
			BlockChatChatState.requestRecentsIfConnected();
		}
	}

	private static void finishPendingView(InboxSnap snap, Path downloadedPath) {
		PendingViewRequest pending;
		synchronized (STATE_LOCK) {
			pending = pendingViewRequest;
			if (pending == null || !Objects.equals(pending.snap().snapId(), snap.snapId())) {
				deleteQuietly(downloadedPath);
				return;
			}
			pendingViewRequest = null;
			UNREAD_BY_SNAP_ID.remove(snap.snapId());
			PREFETCHED_MEDIA.remove(snap.snapId());
			PREFETCH_IN_PROGRESS.remove(snap.snapId());
		}

		long viewedAtMs = System.currentTimeMillis();
		BlockChatWebSocket.snapViewed(snap.snapId());
		BlockChatChatState.markIncomingViewedFromSender(snap.fromUuid(), viewedAtMs);
		pending.future().complete(new ViewedSnap(snap, downloadedPath));
	}

	private static void failPendingView(String message, Throwable error) {
		PendingViewRequest pending;
		synchronized (STATE_LOCK) {
			pending = pendingViewRequest;
			pendingViewRequest = null;
		}
		if (pending == null) {
			return;
		}
		if (error != null) {
			BlockChatClientMod.LOGGER.warn(message, error);
			BlockChatDebugLog.line("inbox", "view_failed " + message, error);
			pending.future().completeExceptionally(error);
			return;
		}
		BlockChatDebugLog.line("inbox", "view_failed " + message);
		pending.future().completeExceptionally(new IllegalStateException(message));
	}

	private static PendingViewRequest clearPendingView(String reason) {
		synchronized (STATE_LOCK) {
			PendingViewRequest pending = pendingViewRequest;
			pendingViewRequest = null;
			if (pending != null && reason != null) {
				pending.future().completeExceptionally(new IllegalStateException(reason));
			}
			return pending;
		}
	}

	private static InboxSnap parseInboxSnap(JsonObject object, InboxSnap fallback) {
		if (object == null) {
			return fallback;
		}
		String snapId = firstNonBlank(
			readString(object, "snap_id"),
			fallback == null ? null : fallback.snapId()
		);
		String fromUuid = BlockChatPlayerUuid.canonicalize(firstNonBlank(
			readString(object, "from"),
			readString(object, "from_uuid"),
			fallback == null ? null : fallback.fromUuid()
		));
		if (snapId == null || fromUuid == null) {
			return null;
		}
		String fromUsername = firstNonBlank(
			readString(object, "from_username"),
			fallback == null ? null : fallback.fromUsername(),
			fromUuid
		);
		String fromSkinUrl = firstNonBlank(
			readNullableString(object, "from_skin_url"),
			readNullableString(object, "skin_url"),
			fallback == null ? null : fallback.fromSkinUrl()
		);
		String mediaTypeRaw = firstNonBlank(
			readString(object, "media_type"),
			fallback == null ? null : fallback.mediaType() == BlockChatChatState.MediaType.VIDEO ? "video" : "image"
		);
		BlockChatChatState.MediaType mediaType = "video".equalsIgnoreCase(mediaTypeRaw)
			? BlockChatChatState.MediaType.VIDEO
			: BlockChatChatState.MediaType.IMAGE;
		String contentType = firstNonBlank(
			readNullableString(object, "content_type"),
			fallback == null ? null : fallback.contentType()
		);
		long sentAtMs = parseTimestampMs(firstNonBlank(
			readString(object, "sent_at"),
			fallback == null ? null : Instant.ofEpochMilli(fallback.sentAtMs()).toString()
		));
		String captionText = firstNonBlank(
			readNullableString(object, "caption_text"),
			fallback == null ? null : fallback.captionText()
		);
		double captionOffsetY = readDouble(
			object,
			"caption_offset_y",
			fallback == null ? 0.5 : fallback.captionOffsetY()
		);
		long expiryMs = readLong(
			object,
			"expiry_ms",
			fallback == null ? 0L : fallback.expiryMs()
		);
		long expiresInSeconds = readLong(
			object,
			"expires_in",
			fallback == null ? 0L : fallback.expiresInSeconds()
		);
		int mediaWidth = readInt(object, "media_width", fallback == null ? 0 : fallback.mediaWidth());
		int mediaHeight = readInt(object, "media_height", fallback == null ? 0 : fallback.mediaHeight());

		return new InboxSnap(
			snapId,
			fromUuid,
			fromUsername,
			fromSkinUrl,
			mediaType,
			contentType,
			sentAtMs,
			captionText,
			captionOffsetY,
			expiryMs,
			expiresInSeconds,
			mediaWidth,
			mediaHeight
		);
	}

	private static String fileExtensionFor(InboxSnap snap) {
		String contentType = snap.contentType();
		if (contentType != null && !contentType.isBlank()) {
			String lowered = contentType.toLowerCase(Locale.ROOT);
			if (lowered.contains("png")) {
				return ".png";
			}
			if (lowered.contains("jpeg") || lowered.contains("jpg")) {
				return ".jpg";
			}
			if (lowered.contains("gif")) {
				return ".gif";
			}
			if (lowered.contains("webm")) {
				return ".webm";
			}
			if (lowered.contains("mp4")) {
				return ".mp4";
			}
		}
		return snap.mediaType() == BlockChatChatState.MediaType.VIDEO ? ".mp4" : ".png";
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

	private static String readString(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		try {
			return object.get(key).getAsString();
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String readNullableString(JsonObject object, String key) {
		String value = readString(object, key);
		return value == null || value.isBlank() ? null : value;
	}

	private static int readInt(JsonObject object, String key, int fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsInt();
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private static long readLong(JsonObject object, String key, long fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsLong();
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private static double readDouble(JsonObject object, String key, double fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return object.get(key).getAsDouble();
		} catch (Exception ignored) {
			return fallback;
		}
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

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private static void pruneIneligiblePrefetchedMediaLocked() {
		List<String> staleIds = new ArrayList<>();
		for (String id : PREFETCHED_MEDIA.keySet()) {
			InboxSnap snap = UNREAD_BY_SNAP_ID.get(id);
			if (snap == null || !shouldPrefetchSnap(snap)) {
				staleIds.add(id);
			}
		}
		for (String id : staleIds) {
			Path path = PREFETCHED_MEDIA.remove(id);
			deleteQuietly(path);
		}
	}

	private static boolean shouldPrefetchSnap(InboxSnap snap) {
		return snap != null
			&& BlockChatSocialState.relationshipFor(snap.fromUuid()) == BlockChatSocialState.FriendRelationship.FRIENDS;
	}

	private record PendingViewRequest(InboxSnap snap, CompletableFuture<ViewedSnap> future) {
	}

	public record InboxSnap(
		String snapId,
		String fromUuid,
		String fromUsername,
		String fromSkinUrl,
		BlockChatChatState.MediaType mediaType,
		String contentType,
		long sentAtMs,
		String captionText,
		double captionOffsetY,
		long expiryMs,
		long expiresInSeconds,
		int mediaWidth,
		int mediaHeight
	) {
	}

	public record ViewedSnap(InboxSnap snap, Path localPath) {
		public String senderUuid() {
			return snap.fromUuid();
		}
	}
}
