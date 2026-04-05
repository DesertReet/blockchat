package desertreet.blockchat.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the WebSocket connection to the BlockChat backend.
 * Handles connect, reconnect, keep-alive pings, and message dispatch.
 */
public final class BlockChatWebSocket {

	private static final Gson GSON = new Gson();
	private static final long PING_INTERVAL_MS = 30_000;
	private static final long RECONNECT_BASE_DELAY_MS = 1_000;
	private static final long RECONNECT_MAX_DELAY_MS = 60_000;
	private static final long DELETE_ACCOUNT_TIMEOUT_MS = 20_000;
	private static final int MAX_RECONNECT_ATTEMPTS = 10;

	private static final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
	private static final AtomicReference<PendingDeleteAccount> pendingDeleteAccount = new AtomicReference<>();
	private static final AtomicBoolean intentionalClose = new AtomicBoolean(false);
	private static final AtomicBoolean connecting = new AtomicBoolean(false);
	private static final AtomicBoolean authFailureHandled = new AtomicBoolean(false);

	private static ScheduledExecutorService scheduler;
	private static ScheduledFuture<?> pingTask;
	private static int reconnectAttempts = 0;
	private static final StringBuilder messageBuffer = new StringBuilder();

	private static final CopyOnWriteArrayList<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

	private BlockChatWebSocket() {
	}

	public interface MessageListener {
		void onMessage(JsonObject message);
		void onConnected();
		void onDisconnected(String reason);
	}

	public static void setMessageListener(MessageListener listener) {
		messageListeners.clear();
		if (listener != null) {
			messageListeners.add(listener);
		}
	}

	public static void addMessageListener(MessageListener listener) {
		if (listener != null) {
			messageListeners.addIfAbsent(listener);
		}
	}

	public static void removeMessageListener(MessageListener listener) {
		if (listener != null) {
			messageListeners.remove(listener);
		}
	}

	/**
	 * Connect to the BlockChat WebSocket server.
	 * Safe to call multiple times; will no-op if already connected or connecting.
	 */
	public static void connect() {
		if (!BlockChatConfig.isLoggedIn()) {
			return;
		}
		if (webSocket.get() != null || connecting.get()) {
			return;
		}
		if (!connecting.compareAndSet(false, true)) {
			return;
		}

		intentionalClose.set(false);
		authFailureHandled.set(false);
		reconnectAttempts = 0;

		doConnect();
	}

	private static void doConnect() {
		String uuid = BlockChatPlayerUuid.canonicalize(BlockChatConfig.getUserUuid());
		String token = BlockChatConfig.getSessionToken();
		if (uuid == null || token.isEmpty()) {
			connecting.set(false);
			return;
		}

		String apiBase = BlockChatAuth.getApiBase();
		String wsBase = apiBase.replace("https://", "wss://").replace("http://", "ws://");
		String wsUrl = wsBase + "/ws?uuid=" + uuid + "&token=" + token;

		BlockChatClientMod.LOGGER.info("BlockChat WS connecting to {}", wsBase + "/ws");

		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

		client.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
				@Override
				public void onOpen(WebSocket ws) {
					BlockChatClientMod.LOGGER.info("BlockChat WS connected");
					webSocket.set(ws);
					connecting.set(false);
					reconnectAttempts = 0;
					startPingTimer();
					ws.request(1);
					requestPreferences();

					Minecraft.getInstance().execute(BlockChatWebSocket::notifyConnected);
				}

				@Override
				public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
					synchronized (messageBuffer) {
						messageBuffer.append(data);
						if (last) {
							String fullMessage = messageBuffer.toString();
							messageBuffer.setLength(0);
							handleMessage(fullMessage);
						}
					}
					ws.request(1);
					return null;
				}

				@Override
				public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
					if (handleAuthSessionClose(statusCode, reason)) {
						return null;
					}
					BlockChatClientMod.LOGGER.info("BlockChat WS closed: {} {}", statusCode, reason);
					cleanup();

					String r = BlockChatStrings.closeReason(reason);
					Minecraft.getInstance().execute(() -> notifyDisconnected(r));

					if (!intentionalClose.get() && BlockChatConfig.isLoggedIn()) {
						scheduleReconnect();
					}
					return null;
				}

				@Override
				public void onError(WebSocket ws, Throwable error) {
					if (handleAuthFailureIfPresent(error)) {
						return;
					}
					BlockChatClientMod.LOGGER.warn("BlockChat WS error", error);
					cleanup();

					Minecraft.getInstance().execute(() -> notifyDisconnected(BlockChatStrings.CONNECTION_ERROR));

					if (!intentionalClose.get() && BlockChatConfig.isLoggedIn()) {
						scheduleReconnect();
					}
				}
			})
			.exceptionally(ex -> {
				if (handleAuthFailureIfPresent(ex)) {
					return null;
				}
				BlockChatClientMod.LOGGER.warn("BlockChat WS connect failed", ex);
				connecting.set(false);

				if (!intentionalClose.get() && BlockChatConfig.isLoggedIn()) {
					scheduleReconnect();
				}
				return null;
			});
	}

	/**
	 * Disconnect from the WebSocket server.
	 */
	public static void disconnect() {
		intentionalClose.set(true);
		WebSocket ws = webSocket.getAndSet(null);
		if (ws != null) {
			try {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnect");
			} catch (Exception e) {
				BlockChatClientMod.LOGGER.debug("Error sending WS close", e);
			}
		}
		cleanup();
	}

	/**
	 * Returns true if the WebSocket is currently connected.
	 */
	public static boolean isConnected() {
		WebSocket ws = webSocket.get();
		return ws != null && !ws.isOutputClosed();
	}

	/**
	 * Send a JSON message over the WebSocket.
	 */
	public static void send(JsonObject message) {
		WebSocket ws = webSocket.get();
		if (ws == null || ws.isOutputClosed()) {
			BlockChatClientMod.LOGGER.warn("BlockChat WS not connected, cannot send: {}", message.get("type"));
			return;
		}
		String json = GSON.toJson(message);
		ws.sendText(json, true);
	}

	/**
	 * Send a typed message with no extra fields.
	 */
	public static void send(String type) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", type);
		send(msg);
	}

	/**
	 * Request a session token refresh.
	 */
	public static void refreshSession() {
		send("session_refresh");
	}

	/**
	 * Revoke the session (logout via WebSocket).
	 */
	public static void revokeSession() {
		send("session_revoke");
	}

	/**
	 * Request permanent BlockChat account deletion and await the server result.
	 */
	public static CompletableFuture<Void> deleteAccount() {
		if (!isConnected()) {
			return CompletableFuture.failedFuture(new IllegalStateException(BlockChatStrings.NOT_CONNECTED_RIGHT_NOW));
		}

		CompletableFuture<Void> future = new CompletableFuture<>();
		PendingDeleteAccount pending = new PendingDeleteAccount(future);
		if (!pendingDeleteAccount.compareAndSet(null, pending)) {
			return CompletableFuture.failedFuture(new IllegalStateException(BlockChatStrings.ACCOUNT_DELETION_ALREADY_IN_PROGRESS));
		}

		pending.timeoutTask = ensureScheduler("BlockChat-WS-Scheduler").schedule(
			() -> completePendingDeleteAccountExceptionally(BlockChatStrings.ACCOUNT_DELETION_TIMED_OUT),
			DELETE_ACCOUNT_TIMEOUT_MS,
			TimeUnit.MILLISECONDS
		);

		send("delete_account");
		if (!isConnected()) {
			completePendingDeleteAccountExceptionally(BlockChatStrings.NOT_CONNECTED_RIGHT_NOW);
		}
		return future;
	}

	/**
	 * Request the friend list.
	 */
	public static void requestFriendList() {
		send("get_friends");
	}

	/**
	 * Request unviewed snaps.
	 */
	public static void requestSnaps() {
		send("get_snaps");
	}

	public static void requestChatRecents() {
		send("get_chat_recents");
	}

	/**
	 * Request current preferences.
	 */
	public static void requestPreferences() {
		send("get_preferences");
	}

	/**
	 * Set a user preference.
	 */
	public static void setPreference(String key, int value) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "set_preference");
		msg.addProperty("key", key);
		msg.addProperty("value", value);
		send(msg);
	}

	/**
	 * Send a friend request.
	 */
	public static void addFriend(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "add_friend");
		msg.addProperty("uuid", uuid);
		send(msg);
	}

	/**
	 * Accept a friend request.
	 */
	public static void acceptFriend(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "accept_friend");
		msg.addProperty("uuid", uuid);
		send(msg);
	}

	/**
	 * Remove a friend.
	 */
	public static void removeFriend(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "remove_friend");
		msg.addProperty("uuid", uuid);
		send(msg);
	}

	public static void searchUser(String username) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "search_user");
		msg.addProperty("username", username);
		send(msg);
	}

	/**
	 * Request a presigned upload URL for sending a snap.
	 */
	public static void sendSnap(
		Collection<String> toUuids,
		String mediaType,
		long mediaSize,
		int mediaWidth,
		int mediaHeight,
		String contentType,
		String captionText,
		double captionOffsetY,
		long expiryMs
	) {
		if (toUuids == null || toUuids.isEmpty()) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "send_snap");
		var toArray = new com.google.gson.JsonArray();
		for (String rawUuid : toUuids) {
			String uuid = BlockChatPlayerUuid.canonicalize(rawUuid);
			if (uuid != null) {
				toArray.add(uuid);
			}
		}
		if (toArray.isEmpty()) {
			return;
		}
		msg.add("to", toArray);
		msg.addProperty("media_type", mediaType);
		msg.addProperty("media_size", mediaSize);
		msg.addProperty("media_width", mediaWidth);
		msg.addProperty("media_height", mediaHeight);
		msg.addProperty("content_type", contentType);
		msg.addProperty("caption_text", captionText == null ? "" : captionText);
		msg.addProperty("caption_offset_y", captionOffsetY);
		msg.addProperty("expiry_ms", expiryMs);
		send(msg);
	}

	/**
	 * Confirm that a snap has been uploaded to R2.
	 */
	public static void snapUploaded(String snapId) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "snap_uploaded");
		msg.addProperty("snap_id", snapId);
		send(msg);
	}

	/**
	 * Request a presigned download URL for viewing a snap.
	 */
	public static void viewSnap(String snapId) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "view_snap");
		msg.addProperty("snap_id", snapId);
		send(msg);
	}

	/**
	 * Mark a snap as viewed after downloading.
	 */
	public static void snapViewed(String snapId) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "snap_viewed");
		msg.addProperty("snap_id", snapId);
		send(msg);
	}

	// ---- internal ----

	private static void handleMessage(String json) {
		try {
			JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
			String type = msg.has("type") ? msg.get("type").getAsString() : "";

			switch (type) {
				case "pong" -> {
					// Keep-alive response, no action needed
				}
				case "session_token" -> {
					// Session was refreshed - store new token
					String newToken = msg.get("token").getAsString();
					BlockChatConfig.setSessionToken(newToken);
					BlockChatConfig.save();
					BlockChatClientMod.LOGGER.info("BlockChat session token refreshed");
				}
				case "error" -> {
					String code = msg.has("code") ? msg.get("code").getAsString() : "unknown";
					String message = msg.has("message") ? msg.get("message").getAsString() : "";
					BlockChatClientMod.LOGGER.warn("BlockChat WS error: {} - {}", code, message);
					BlockChatDebugLog.line("websocket", "server_error code=" + code + " message=" + abbreviateWsDetail(message));
					maybeFailPendingDeleteAccount(code, message);
				}
				case "account_deleted" -> {
					Minecraft.getInstance().execute(BlockChatWebSocket::completePendingDeleteAccountSuccessfully);
				}
				default -> {
					// Pass all messages (including errors) to the listener
				}
			}

			Minecraft.getInstance().execute(() -> notifyMessage(msg));
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to parse BlockChat WS message: {}", json, e);
			BlockChatDebugLog.line("websocket", "parse_failed payload=" + abbreviateWsPayload(json), e);
		}
	}

	private static void notifyConnected() {
		for (MessageListener listener : messageListeners) {
			listener.onConnected();
		}
	}

	private static void notifyDisconnected(String reason) {
		completePendingDeleteAccountExceptionally(disconnectDeleteAccountMessage(reason));
		for (MessageListener listener : messageListeners) {
			listener.onDisconnected(reason);
		}
	}

	private static void notifyMessage(JsonObject message) {
		for (MessageListener listener : messageListeners) {
			listener.onMessage(message);
		}
	}

	private static void startPingTimer() {
		stopPingTimer();
		ensureScheduler("BlockChat-WS-Ping");
		pingTask = scheduler.scheduleAtFixedRate(() -> {
			if (isConnected()) {
				try {
					send("ping");
				} catch (Exception e) {
					BlockChatClientMod.LOGGER.debug("Ping send failed", e);
				}
			}
		}, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	private static void stopPingTimer() {
		if (pingTask != null) {
			pingTask.cancel(false);
			pingTask = null;
		}
	}

	private static void scheduleReconnect() {
		if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
			BlockChatClientMod.LOGGER.warn("BlockChat WS max reconnect attempts reached");
			BlockChatDebugLog.line("websocket", "reconnect_aborted max_attempts=" + MAX_RECONNECT_ATTEMPTS);
			return;
		}

		long delay = Math.min(
			RECONNECT_BASE_DELAY_MS * (1L << reconnectAttempts),
			RECONNECT_MAX_DELAY_MS
		);
		reconnectAttempts++;

		BlockChatClientMod.LOGGER.info("BlockChat WS reconnecting in {}ms (attempt {})", delay, reconnectAttempts);

		ensureScheduler("BlockChat-WS-Reconnect");
		scheduler.schedule(() -> {
			if (!intentionalClose.get() && BlockChatConfig.isLoggedIn() && webSocket.get() == null) {
				connecting.set(false); // Reset so doConnect can proceed
				doConnect();
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private static void cleanup() {
		webSocket.set(null);
		connecting.set(false);
		stopPingTimer();
		synchronized (messageBuffer) {
			messageBuffer.setLength(0);
		}
	}

	private static boolean handleAuthSessionClose(int statusCode, String reason) {
		if (intentionalClose.get()) {
			return false;
		}
		if (!isAuthSessionClose(statusCode, reason)) {
			return false;
		}
		String message = describeAuthSessionFailure(statusCode, reason);
		handleAuthFailure(message, null);
		return true;
	}

	private static boolean handleAuthFailureIfPresent(Throwable error) {
		if (intentionalClose.get()) {
			return false;
		}
		WebSocketHandshakeException handshake = findHandshakeException(error);
		if (handshake != null) {
			int statusCode = handshake.getResponse() == null ? -1 : handshake.getResponse().statusCode();
			if (statusCode == 401 || statusCode == 403) {
				handleAuthFailure(BlockChatStrings.authenticationFailed(statusCode), error);
				return true;
			}
		}

		String reason = error == null ? null : error.getMessage();
		if (isAuthSessionReason(reason)) {
			handleAuthFailure(describeAuthSessionFailure(-1, reason), error);
			return true;
		}
		return false;
	}

	private static void handleAuthFailure(String reason, Throwable error) {
		if (!authFailureHandled.compareAndSet(false, true)) {
			return;
		}

		intentionalClose.set(true);
		if (error != null) {
			BlockChatClientMod.LOGGER.warn("BlockChat WS auth/session failure: {}", reason, error);
		} else {
			BlockChatClientMod.LOGGER.warn("BlockChat WS auth/session failure: {}", reason);
		}
		cleanup();

		Minecraft.getInstance().execute(() -> {
			completePendingDeleteAccountExceptionally(reason == null || reason.isBlank()
				? BlockChatStrings.ACCOUNT_DELETION_FAILED
				: reason);
			BlockChatAuth.logoutDueToAuthFailure(reason);
			notifyDisconnected(reason);
		});
	}

	private static ScheduledExecutorService ensureScheduler(String threadName) {
		if (scheduler == null || scheduler.isShutdown()) {
			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, threadName);
				t.setDaemon(true);
				return t;
			});
		}
		return scheduler;
	}

	private static void maybeFailPendingDeleteAccount(String code, String message) {
		PendingDeleteAccount pending = pendingDeleteAccount.get();
		if (pending == null) {
			return;
		}

		String detail = "";
		if (message != null && !message.isBlank()) {
			detail = message.trim();
		} else if (code != null && !code.isBlank() && !"unknown".equalsIgnoreCase(code)) {
			detail = humanizeReason(code);
		}

		String failureMessage = detail.isEmpty()
			? BlockChatStrings.ACCOUNT_DELETION_FAILED
			: BlockChatStrings.noAccountDeletionFailureDetail(detail);
		Minecraft.getInstance().execute(() -> completePendingDeleteAccountExceptionally(failureMessage));
	}

	private static void completePendingDeleteAccountSuccessfully() {
		PendingDeleteAccount pending = pendingDeleteAccount.getAndSet(null);
		if (pending == null) {
			return;
		}
		pending.cancelTimeout();
		pending.future.complete(null);
	}

	private static void completePendingDeleteAccountExceptionally(String message) {
		PendingDeleteAccount pending = pendingDeleteAccount.getAndSet(null);
		if (pending == null) {
			return;
		}
		pending.cancelTimeout();
		pending.future.completeExceptionally(new IllegalStateException(message));
	}

	private static String disconnectDeleteAccountMessage(String reason) {
		String detail = humanizeReason(reason);
		return BlockChatStrings.accountDeletionDisconnected(detail);
	}

	private static WebSocketHandshakeException findHandshakeException(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof WebSocketHandshakeException handshakeException) {
				return handshakeException;
			}
			current = current.getCause();
		}
		return null;
	}

	private static boolean isAuthSessionClose(int statusCode, String reason) {
		if (statusCode == 401 || statusCode == 403) {
			return true;
		}
		return isAuthSessionReason(reason);
	}

	private static boolean isAuthSessionReason(String reason) {
		if (reason == null) {
			return false;
		}
		String normalized = reason.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		if (normalized.isEmpty()) {
			return false;
		}
		return normalized.contains("invalid_session")
			|| normalized.contains("session_expired")
			|| normalized.contains("expired_session")
			|| normalized.contains("session_invalid")
			|| normalized.contains("token_expired")
			|| normalized.contains("expired_token")
			|| normalized.contains("invalid_token")
			|| normalized.contains("authentication_failed")
			|| normalized.contains("auth_failed")
			|| normalized.contains("unauthorized")
			|| normalized.contains("forbidden")
			|| normalized.contains("invalid_credentials")
			|| normalized.contains("credentials_invalid");
	}

	private static String describeAuthSessionFailure(int statusCode, String reason) {
		String cleanReason = humanizeReason(reason);
		if (statusCode == 401 || statusCode == 403) {
			return BlockChatStrings.authenticationFailed(statusCode);
		}
		return BlockChatStrings.authenticationSessionError(cleanReason);
	}

	private static String humanizeReason(String reason) {
		if (reason == null) {
			return "";
		}
		String cleaned = reason.trim().replaceAll("[^A-Za-z0-9]+", " ").replaceAll("\\s+", " ").trim();
		return cleaned;
	}

	private static final int MAX_WS_LOG_LEN = 480;

	private static String abbreviateWsPayload(String json) {
		if (json == null) {
			return "<null>";
		}
		String compact = json.replace('\n', ' ').replace('\r', ' ').trim();
		if (compact.length() <= MAX_WS_LOG_LEN) {
			return compact;
		}
		return compact.substring(0, MAX_WS_LOG_LEN - 3) + "...";
	}

	private static String abbreviateWsDetail(String message) {
		if (message == null) {
			return "";
		}
		String compact = message.replace('\n', ' ').replace('\r', ' ').trim();
		if (compact.length() <= 200) {
			return compact;
		}
		return compact.substring(0, 197) + "...";
	}

	private static final class PendingDeleteAccount {
		private final CompletableFuture<Void> future;
		private ScheduledFuture<?> timeoutTask;

		private PendingDeleteAccount(CompletableFuture<Void> future) {
			this.future = future;
		}

		private void cancelTimeout() {
			if (timeoutTask != null) {
				timeoutTask.cancel(false);
				timeoutTask = null;
			}
		}
	}
}
