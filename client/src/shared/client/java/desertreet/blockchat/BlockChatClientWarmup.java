package desertreet.blockchat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.chat.BlockChatChatState;
import desertreet.blockchat.chat.BlockChatInboxState;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;
import desertreet.blockchat.social.BlockChatSocialState;
import desertreet.blockchat.ui.BlockChatScreen;

public final class BlockChatClientWarmup {
	private static final int PRELOAD_INTERVAL_TICKS = 20;
	private static boolean initialized;
	private static boolean wasInWorld;
	private static int tickCounter;

	private BlockChatClientWarmup() {
	}

	public static void register() {
		if (initialized) {
			return;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			client.execute(() -> onWorldJoined(client))
		);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			client.execute(BlockChatClientWarmup::onWorldLeft)
		);
		initialized = true;
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			if (wasInWorld) {
				onWorldLeft();
				BlockChatWebSocket.disconnect();
			}
			return;
		}

		if (!wasInWorld) {
			onWorldJoined(client);
		}

		if (!BlockChatAuth.isLoggedIn()) {
			return;
		}

		if (!(client.screen instanceof BlockChatScreen)) {
			PlayerLookupCache.tick();
		}

		tickCounter++;
		if (tickCounter % PRELOAD_INTERVAL_TICKS == 0) {
			preloadChatFaces();
			BlockChatInboxState.refreshEligibleUnreadPrefetches();
		}
	}

	private static void onWorldJoined(Minecraft client) {
		wasInWorld = true;
		if (!BlockChatAuth.isLoggedIn()) {
			return;
		}

		BlockChatSocialState.installWebSocketListener();
		BlockChatChatState.installWebSocketListener();
		BlockChatInboxState.installWebSocketListener();
		BlockChatWebSocket.connect();
	}

	private static void onWorldLeft() {
		wasInWorld = false;
		tickCounter = 0;
	}

	private static void preloadChatFaces() {
		for (BlockChatSocialState.FriendRecord friend : BlockChatSocialState.friendsByUuid().values()) {
			preloadFace(friend.username(), friend.uuid(), friend.skinUrl());
		}
		for (BlockChatChatState.RecentContact recent : BlockChatChatState.recentsByUuid().values()) {
			preloadFace(recent.username(), recent.uuid(), recent.skinUrl());
		}
	}

	private static void preloadFace(String username, String uuid, String skinUrl) {
		String resolvedSkin = PlayerLookupCache.resolveSkinUrl(username, uuid, skinUrl);
		SkinHelper.getFaceTexture(resolvedSkin, uuid);
	}
}
