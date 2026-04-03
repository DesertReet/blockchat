package desertreet.blockchat.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.hud.BlockChatHud;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BlockChatSocialState {
	private static final Map<String, FriendRecord> FRIENDS_BY_UUID = new HashMap<>();
	private static final BlockChatWebSocket.MessageListener WEBSOCKET_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
			connected = true;
			connectionMessage = null;
			BlockChatWebSocket.requestFriendList();
		}

		@Override
		public void onDisconnected(String reason) {
			connected = false;
			connectionMessage = reason == null || reason.isBlank() ? BlockChatStrings.DISCONNECTED : reason;
			searching = false;
			pendingFriendAction = null;
		}
	};

	private static boolean listenerInstalled;
	private static boolean connected;
	private static String connectionMessage;
	private static boolean searching;
	private static String activeSearchQuery;
	private static SearchResult searchResult;
	private static String searchFeedbackMessage;
	private static PendingFriendAction pendingFriendAction;

	private BlockChatSocialState() {
	}

	public static void installWebSocketListener() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(WEBSOCKET_LISTENER);
		listenerInstalled = true;
	}

	public static void clear() {
		FRIENDS_BY_UUID.clear();
		connected = false;
		connectionMessage = null;
		clearSearchState();
		pendingFriendAction = null;
	}

	public static void requestFriendListIfConnected() {
		if (BlockChatWebSocket.isConnected()) {
			connected = true;
			connectionMessage = null;
			BlockChatWebSocket.requestFriendList();
		}
	}

	public static Map<String, FriendRecord> friendsByUuid() {
		return Collections.unmodifiableMap(FRIENDS_BY_UUID);
	}

	public static FriendRelationship relationshipFor(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return FriendRelationship.NONE;
		}
		FriendRecord record = FRIENDS_BY_UUID.get(uuid);
		return record == null ? FriendRelationship.NONE : record.relationship();
	}

	public static int incomingRequestCount() {
		int count = 0;
		for (FriendRecord record : FRIENDS_BY_UUID.values()) {
			if (record.isIncomingRequest()) {
				count++;
			}
		}
		return count;
	}

	public static List<FriendRecord> incomingFriendRequests() {
		List<FriendRecord> requests = new ArrayList<>();
		for (FriendRecord record : FRIENDS_BY_UUID.values()) {
			if (record.isIncomingRequest()) {
				requests.add(record);
			}
		}
		requests.sort(
			Comparator.comparing(FriendRecord::username, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(FriendRecord::uuid)
		);
		return Collections.unmodifiableList(requests);
	}

	public static boolean isConnected() {
		return connected || BlockChatWebSocket.isConnected();
	}

	public static String connectionMessage() {
		return connectionMessage;
	}

	public static boolean isSearching() {
		return searching;
	}

	public static SearchResult searchResult() {
		return searchResult;
	}

	public static String searchFeedbackMessage() {
		return searchFeedbackMessage;
	}

	public static void submitSearch(String username) {
		String query = username == null ? "" : username.trim();
		if (query.isEmpty()) {
			searching = false;
			activeSearchQuery = null;
			searchResult = null;
			searchFeedbackMessage = BlockChatStrings.ENTER_USERNAME_TO_SEARCH;
			return;
		}
		if (!BlockChatWebSocket.isConnected()) {
			searching = false;
			activeSearchQuery = query;
			searchResult = null;
			searchFeedbackMessage = BlockChatStrings.NOT_CONNECTED_RIGHT_NOW;
			return;
		}

		searching = true;
		activeSearchQuery = query;
		searchResult = null;
		searchFeedbackMessage = null;
		BlockChatWebSocket.searchUser(query);
	}

	public static void requestAddFriend(String uuid, String username, String skinUrl, FriendActionOrigin origin) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		pendingFriendAction = new PendingFriendAction(origin, uuid, FRIENDS_BY_UUID.get(uuid));
		PlayerLookupCache.rememberFromSocial(username, uuid, skinUrl);
		BlockChatWebSocket.addFriend(uuid);
		upsertFriend(uuid, username, skinUrl, FriendRelationship.PENDING, FriendRequestDirection.OUTGOING);
		if (origin == FriendActionOrigin.SEARCH_RESULT && searchResult != null && uuid.equals(searchResult.uuid())) {
			searchResult = new SearchResult(searchResult.query(), uuid, username, skinUrl);
		}
	}

	public static void acceptIncomingFriendRequest(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		BlockChatWebSocket.acceptFriend(uuid);
		clearIncomingRequest(uuid);
	}

	public static void ignoreIncomingFriendRequest(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		BlockChatWebSocket.removeFriend(uuid);
		clearIncomingRequest(uuid);
	}

	private static void handleMessage(JsonObject message) {
		if (message == null || !message.has("type")) {
			return;
		}

		String type = message.get("type").getAsString();
		switch (type) {
			case "friend_list" -> handleFriendList(message);
			case "friend_request" -> handleIncomingFriendRequest(message);
			case "friend_request_sent" -> handleOutgoingFriendRequestSent(message);
			case "friend_added" -> handleFriendAdded(message);
			case "friend_removed" -> handleFriendRemoved(message);
			case "user_search_result" -> handleUserSearchResult(message);
			case "error" -> handleError(message);
			default -> {
			}
		}
	}

	private static void handleFriendList(JsonObject message) {
		FRIENDS_BY_UUID.clear();
		JsonArray friends = message.getAsJsonArray("friends");
		if (friends == null) {
			return;
		}
		for (JsonElement friendElement : friends) {
			if (!friendElement.isJsonObject()) {
				continue;
			}
			JsonObject friend = friendElement.getAsJsonObject();
			String uuid = BlockChatPlayerUuid.canonicalize(readString(friend, "uuid"));
			if (uuid == null) {
				continue;
			}
			FriendState state = stateFromStatus(readString(friend, "status"));
			String fname = readString(friend, "username");
			String fskin = readNullableString(friend, "skin_url");
			PlayerLookupCache.rememberFromSocial(fname, uuid, fskin);
			upsertFriend(
				uuid,
				fname,
				fskin,
				state.relationship(),
				state.requestDirection()
			);
		}
	}

	private static void handleIncomingFriendRequest(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "from"));
		if (uuid == null) {
			return;
		}
		String skinUrl = readNullableString(message, "from_skin_url");
		if (skinUrl == null) {
			skinUrl = readNullableString(message, "skin_url");
		}
		String fromName = readString(message, "from_username");
		PlayerLookupCache.rememberFromSocial(fromName, uuid, skinUrl);
		upsertFriend(
			uuid,
			fromName,
			skinUrl,
			FriendRelationship.PENDING,
			FriendRequestDirection.INCOMING
		);
	}

	private static void handleOutgoingFriendRequestSent(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "uuid"));
		if (uuid == null) {
			uuid = BlockChatPlayerUuid.canonicalize(readString(message, "to"));
		}
		if (uuid == null) {
			return;
		}

		clearPendingFriendAction(uuid);

		String skinFromMsg = readNullableString(message, "skin_url");
		if (skinFromMsg == null) {
			skinFromMsg = readNullableString(message, "from_skin_url");
		}
		String username = readString(message, "username");
		PlayerLookupCache.rememberFromSocial(username, uuid, skinFromMsg);
		upsertFriend(
			uuid,
			username,
			skinFromMsg,
			FriendRelationship.PENDING,
			FriendRequestDirection.OUTGOING
		);
	}

	private static void handleFriendAdded(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "uuid"));
		if (uuid == null) {
			return;
		}
		clearPendingFriendAction(uuid);
		FriendRecord existing = FRIENDS_BY_UUID.get(uuid);
		boolean localUserHadOutgoingRequest = existing != null && existing.isOutgoingRequest();
		String skinFromMsg = readNullableString(message, "skin_url");
		if (skinFromMsg == null) {
			skinFromMsg = readNullableString(message, "from_skin_url");
		}
		String skinUrl = skinFromMsg != null ? skinFromMsg : existing == null ? null : existing.skinUrl();
		String username = readString(message, "username");
		upsertFriend(
			uuid,
			username,
			skinUrl,
			FriendRelationship.FRIENDS,
			FriendRequestDirection.NONE
		);
		if (localUserHadOutgoingRequest) {
			String resolvedSkin = PlayerLookupCache.resolveSkinUrl(username, uuid, skinUrl);
			BlockChatHud.showFriendAcceptedRequesterToast(username, uuid, resolvedSkin);
		}
	}

	private static void handleFriendRemoved(JsonObject message) {
		String uuid = BlockChatPlayerUuid.canonicalize(readString(message, "uuid"));
		if (uuid == null) {
			return;
		}
		clearPendingFriendAction(uuid);
		FRIENDS_BY_UUID.remove(uuid);
	}

	private static void handleUserSearchResult(JsonObject message) {
		String query = readString(message, "query");
		if (query == null || activeSearchQuery == null || !query.equalsIgnoreCase(activeSearchQuery)) {
			return;
		}
		searching = false;
		searchFeedbackMessage = null;

		JsonElement userElement = message.get("user");
		if (userElement == null || userElement.isJsonNull()) {
			searchResult = null;
			searchFeedbackMessage = BlockChatStrings.noMinecraftAccountFound(query);
			return;
		}
		JsonObject user = userElement.getAsJsonObject();
		searchResult = new SearchResult(
			query,
			BlockChatPlayerUuid.canonicalize(readString(user, "uuid")),
			readString(user, "username"),
			readNullableString(user, "skin_url")
		);
	}

	private static void handleError(JsonObject message) {
		String code = readString(message, "code");
		String errorMessage = readString(message, "message");
		if (code == null) {
			return;
		}

		boolean friendError = isFriendError(code);
		boolean searchError = isSearchError(code);
		if (friendError || searchError) {
			String em = errorMessage == null ? "" : errorMessage.replace('\n', ' ').trim();
			if (em.length() > 200) {
				em = em.substring(0, 197) + "...";
			}
			BlockChatDebugLog.line(
				"social",
				"server_error code=" + code + " friend_op=" + friendError + " search=" + searchError + " message=" + em
			);
		}

		if (friendError && pendingFriendAction != null) {
			FriendActionOrigin origin = pendingFriendAction.origin;
			restorePendingFriendAction();
			if (origin == FriendActionOrigin.SEARCH_RESULT) {
				searching = false;
				searchFeedbackMessage = errorMessage == null || errorMessage.isBlank() ? BlockChatStrings.FRIEND_REQUEST_FAILED : errorMessage;
			}
			return;
		}

		if (searchError) {
			searching = false;
			searchFeedbackMessage = errorMessage == null || errorMessage.isBlank() ? BlockChatStrings.SEARCH_FAILED : errorMessage;
			searchResult = null;
			activeSearchQuery = null;
		}
	}

	private static void clearSearchState() {
		searching = false;
		activeSearchQuery = null;
		searchResult = null;
		searchFeedbackMessage = null;
	}

	private static void restorePendingFriendAction() {
		if (pendingFriendAction == null) {
			return;
		}
		if (pendingFriendAction.previousRecord == null) {
			FRIENDS_BY_UUID.remove(pendingFriendAction.uuid);
		} else {
			FRIENDS_BY_UUID.put(pendingFriendAction.uuid, pendingFriendAction.previousRecord);
		}
		pendingFriendAction = null;
	}

	private static void clearPendingFriendAction(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		if (pendingFriendAction != null && pendingFriendAction.uuid.equals(uuid)) {
			pendingFriendAction = null;
		}
	}

	private static boolean isFriendError(String code) {
		return "invalid_friend".equals(code)
			|| "already_exists".equals(code)
			|| "friend_requests_disabled".equals(code)
			|| "friend_request_failed".equals(code)
			|| "user_not_found".equals(code);
	}

	private static boolean isSearchError(String code) {
		return code.contains("search")
			|| "invalid_username".equals(code)
			|| ("user_not_found".equals(code) && pendingFriendAction == null);
	}

	private static void upsertFriend(String uuid, String username, String skinUrl, FriendRelationship relationship, FriendRequestDirection requestDirection) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		FriendRecord existing = FRIENDS_BY_UUID.get(uuid);
		String resolvedUsername = username != null && !username.isBlank()
			? username
			: existing == null ? uuid : existing.username();
		String resolvedSkinUrl = skinUrl != null && !skinUrl.isBlank()
			? skinUrl
			: existing == null ? null : existing.skinUrl();
		FRIENDS_BY_UUID.put(uuid, new FriendRecord(uuid, resolvedUsername, resolvedSkinUrl, relationship, requestDirection));
	}

	private static FriendState stateFromStatus(String status) {
		if ("accepted".equals(status)) {
			return new FriendState(FriendRelationship.FRIENDS, FriendRequestDirection.NONE);
		}
		if ("pending_outgoing".equals(status)) {
			return new FriendState(FriendRelationship.PENDING, FriendRequestDirection.OUTGOING);
		}
		if ("pending_incoming".equals(status)) {
			return new FriendState(FriendRelationship.PENDING, FriendRequestDirection.INCOMING);
		}
		return new FriendState(FriendRelationship.NONE, FriendRequestDirection.NONE);
	}

	private static void clearIncomingRequest(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return;
		}
		FriendRecord record = FRIENDS_BY_UUID.get(uuid);
		if (record != null && record.isIncomingRequest()) {
			FRIENDS_BY_UUID.remove(uuid);
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

	public enum FriendRelationship {
		NONE,
		PENDING,
		FRIENDS
	}

	public record FriendRecord(String uuid, String username, String skinUrl, FriendRelationship relationship, FriendRequestDirection requestDirection) {
		public boolean isIncomingRequest() {
			return relationship == FriendRelationship.PENDING && requestDirection == FriendRequestDirection.INCOMING;
		}

		public boolean isOutgoingRequest() {
			return relationship == FriendRelationship.PENDING && requestDirection == FriendRequestDirection.OUTGOING;
		}
	}

	public record SearchResult(String query, String uuid, String username, String skinUrl) {
	}

	public enum FriendActionOrigin {
		ONLINE_LIST,
		SEARCH_RESULT
	}

	public enum FriendRequestDirection {
		NONE,
		INCOMING,
		OUTGOING
	}

	private record FriendState(FriendRelationship relationship, FriendRequestDirection requestDirection) {
	}

	private record PendingFriendAction(FriendActionOrigin origin, String uuid, FriendRecord previousRecord) {
	}
}
