package desertreet.blockchat.hud;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.capture.VideoRecorder;
import desertreet.blockchat.compat.BlockChatToastCompat;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.preferences.BlockChatPreferenceState;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;

import java.util.ArrayDeque;

public final class BlockChatHud {

	/** One id per capture toast so {@link SystemToast} tokens stay unique and stack in {@link net.minecraft.client.gui.components.toasts.ToastManager}. */
	private static final ArrayDeque<SystemToast.SystemToastId> ACTIVE_CAPTURE_TOAST_IDS = new ArrayDeque<>();
	private static final int BORDER_RED = 0xFFE84040;
	private static final int BORDER_PURPLE = 0xFF9B59B6;
	private static final ArrayDeque<PendingFaceToast> PENDING_FACE_TOASTS = new ArrayDeque<>();
	private static final BlockChatWebSocket.MessageListener SOCIAL_TOAST_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleSocialToastMessage(message);
		}

		@Override
		public void onConnected() {
		}

		@Override
		public void onDisconnected(String reason) {
		}
	};

	private static boolean listenerInstalled;

	private BlockChatHud() {
	}

	public static void register() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(SOCIAL_TOAST_LISTENER);
		listenerInstalled = true;
	}

	public static void tick() {
		if (PENDING_FACE_TOASTS.isEmpty()) {
			return;
		}
		int remaining = PENDING_FACE_TOASTS.size();
		for (int i = 0; i < remaining; i++) {
			PendingFaceToast pending = PENDING_FACE_TOASTS.pollFirst();
			if (pending == null) {
				continue;
			}
			if (!tryShowFriendProfileToast(pending)) {
				PENDING_FACE_TOASTS.addLast(pending);
			}
		}
	}

	public static void showCaptureToast(Component title, Component subtitle) {
		Minecraft client = Minecraft.getInstance();
		Object toastHost = BlockChatToastCompat.getToastHost(client);
		// Slight per-toast duration spread so SystemToastId stays distinct if the type compares by display time only.
		long displayMs = 5000L + (BlockChatToastTokens.nextMonotonicToken() % 900L);
		SystemToast.SystemToastId id = new SystemToast.SystemToastId(displayMs);
		BlockChatToastCompat.addSystemToast(toastHost, id, title, subtitle);
		ACTIVE_CAPTURE_TOAST_IDS.addLast(id);
	}

	public static boolean isCaptureToastActive() {
		Minecraft client = Minecraft.getInstance();
		pruneEndedCaptureToasts(BlockChatToastCompat.getToastHost(client));
		return !ACTIVE_CAPTURE_TOAST_IDS.isEmpty();
	}

	public static void dismissCaptureToast() {
		Minecraft client = Minecraft.getInstance();
		Object toastHost = BlockChatToastCompat.getToastHost(client);
		pruneEndedCaptureToasts(toastHost);
		while (!ACTIVE_CAPTURE_TOAST_IDS.isEmpty()) {
			SystemToast.SystemToastId id = ACTIVE_CAPTURE_TOAST_IDS.pollLast();
			if (id != null) {
				BlockChatToastCompat.forceHideSystemToast(toastHost, id);
			}
		}
	}

	private static void pruneEndedCaptureToasts(Object toastHost) {
		int n = ACTIVE_CAPTURE_TOAST_IDS.size();
		for (int i = 0; i < n; i++) {
			SystemToast.SystemToastId id = ACTIVE_CAPTURE_TOAST_IDS.pollFirst();
			if (id == null) {
				continue;
			}
			if (BlockChatToastCompat.hasToast(toastHost, SystemToast.class, id)) {
				ACTIVE_CAPTURE_TOAST_IDS.addLast(id);
			}
		}
	}

	public static void showFriendRequestToast(String uuid, String username, String skinUrl) {
		showFriendProfileToast(BlockChatStrings.TOAST_NEW_FRIEND_REQUEST, username, username, uuid, skinUrl, -1);
	}

	/**
	 * Shown when someone accepts our outgoing friend request (not when we accept an incoming request).
	 */
	public static void showFriendAcceptedRequesterToast(String friendUsername, String friendUuid, String skinUrl) {
		showFriendProfileToast(BlockChatStrings.TOAST_NEW_FRIEND, friendUsername, friendUsername, friendUuid, skinUrl, -1);
	}

	/**
	 * Top-right friend face toast; respects {@link BlockChatPreferenceState#isNotificationsEnabled()}.
	 */
	public static void showFriendProfileToast(String title, String subtitle, String uuid, String skinUrl) {
		showFriendProfileToast(title, subtitle, subtitle, uuid, skinUrl, -1);
	}

	public static void showFriendProfileToast(String title, String subtitle, String uuid, String skinUrl, int borderColor) {
		showFriendProfileToast(title, subtitle, subtitle, uuid, skinUrl, borderColor);
	}

	private static void showFriendProfileToast(
		String title,
		String subtitle,
		String lookupUsername,
		String uuid,
		String skinUrl,
		int borderColor
	) {
		if (!BlockChatPreferenceState.isNotificationsEnabled()) {
			return;
		}
		primeFriendFace(lookupUsername, uuid, skinUrl);
		PendingFaceToast pending = new PendingFaceToast(title, subtitle, lookupUsername, uuid, skinUrl, borderColor);
		if (!tryShowFriendProfileToast(pending)) {
			PENDING_FACE_TOASTS.addLast(pending);
		}
	}

	private static void primeFriendFace(String lookupUsername, String uuid, String skinUrl) {
		if (lookupUsername != null && !lookupUsername.isBlank()) {
			PlayerLookupCache.rememberFromSocial(lookupUsername, uuid, skinUrl);
		}
		String resolvedSkin = skinUrl;
		if (resolvedSkin == null || resolvedSkin.isBlank()) {
			resolvedSkin = PlayerLookupCache.resolveSkinUrl(lookupUsername, uuid, null);
		}
		if (resolvedSkin != null && !resolvedSkin.isBlank()) {
			SkinHelper.getFaceTexture(resolvedSkin, uuid);
		}
	}

	private static boolean tryShowFriendProfileToast(PendingFaceToast pending) {
		if (pending == null) {
			return true;
		}
		if (!BlockChatPreferenceState.isNotificationsEnabled()) {
			return true;
		}
		String skinUrl = pending.skinUrl();
		if (skinUrl == null || skinUrl.isBlank()) {
			String lookupSkin = PlayerLookupCache.resolveSkinUrl(pending.lookupUsername(), pending.uuid(), null);
			if (lookupSkin != null && !lookupSkin.isBlank()) {
				skinUrl = lookupSkin;
			}
		}
		if (SkinHelper.getFaceTexture(skinUrl, pending.uuid()) == null) {
			return false;
		}
		playFriendNotificationSound();
		BlockChatToastCompat.addToast(
			BlockChatToastCompat.getToastHost(Minecraft.getInstance()),
			new FriendProfileToast(pending.title(), pending.subtitle(), pending.uuid(), skinUrl, pending.borderColor())
		);
		return true;
	}

	private static void playFriendNotificationSound() {
		Minecraft.getInstance().getSoundManager().play(
			SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.85F, 0.45F)
		);
	}

	public static void renderRecordingIndicator(GuiGraphics graphics) {
		if (!VideoRecorder.isRecording()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		int screenWidth = graphics.guiWidth();
		long elapsed = VideoRecorder.getElapsedMs();
		int seconds = (int) (elapsed / 1000);
		int minutes = seconds / 60;
		seconds = seconds % 60;
		String timeStr = String.format("%02d:%02d", minutes, seconds);
		String recText = BlockChatStrings.recordingIndicator(timeStr);

		int textWidth = client.font.width(recText);
		int padding = 4;
		int totalWidth = padding + 6 + 4 + textWidth + padding;
		int totalHeight = 12 + padding * 2;
		int x = screenWidth - totalWidth - 6;
		int y = 6;

		graphics.fill(x, y, x + totalWidth, y + totalHeight, 0xCC1A1A1A);

		long tick = client.level != null ? client.level.getGameTime() : System.currentTimeMillis() / 50;
		float pulse = 0.6F + 0.4F * Mth.sin(tick * 0.3F);
		int red = (int) (255 * pulse);
		int dotColor = 0xFF000000 | (red << 16);
		int dotX = x + padding + 3;
		int dotY = y + totalHeight / 2;
		int dotRadius = 3;
		graphics.fill(dotX - dotRadius, dotY - dotRadius, dotX + dotRadius, dotY + dotRadius, dotColor);

		int textX = x + padding + 6 + 4;
		int textY = y + padding;
		graphics.drawString(client.font, recText, textX, textY, 0xFFFF4444);
	}

	private static void handleSocialToastMessage(JsonObject message) {
		if (message == null || !message.has("type")) {
			return;
		}
		String type = message.get("type").getAsString();
		switch (type) {
			case "friend_request" -> {
				String uuid = readString(message, "from");
				String username = readString(message, "from_username");
				String messageSkin = readSkinUrlFromSocialMessage(message);
				String skinUrl = username != null
					? PlayerLookupCache.resolveSkinUrl(username, uuid, messageSkin)
					: messageSkin;
				showFriendProfileToast(BlockChatStrings.TOAST_NEW_FRIEND_REQUEST, username, username, uuid, skinUrl, -1);
			}
			case "snap_received" -> {
				String uuid = readString(message, "from");
				String username = readString(message, "from_username");
				String messageSkin = readSkinUrlFromSocialMessage(message);
				String skinUrl = username != null
					? PlayerLookupCache.resolveSkinUrl(username, uuid, messageSkin)
					: messageSkin;
				String subtitle = username == null || username.isBlank() ? BlockChatStrings.UNKNOWN_PLAYER_NAME : username;
				String mediaType = readString(message, "media_type");
				int borderColor = "video".equalsIgnoreCase(mediaType) ? BORDER_PURPLE : BORDER_RED;
				showFriendProfileToast(BlockChatStrings.TOAST_NEW_BLOCK, subtitle, username, uuid, skinUrl, borderColor);
			}
			default -> {
			}
		}
	}

	private static String readSkinUrlFromSocialMessage(JsonObject message) {
		String skinUrl = readNullableString(message, "from_skin_url");
		if (skinUrl == null) {
			skinUrl = readNullableString(message, "skin_url");
		}
		return skinUrl;
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

	private record PendingFaceToast(
		String title,
		String subtitle,
		String lookupUsername,
		String uuid,
		String skinUrl,
		int borderColor
	) {
	}
}
