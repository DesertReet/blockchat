package desertreet.blockchat.compat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class BlockChatPlayerMessages {

	private BlockChatPlayerMessages() {
	}

	public static void show(LocalPlayer player, Component message, boolean overlay) {
		if (player == null || message == null) {
			return;
		}
		player.displayClientMessage(message, overlay);
	}
}
