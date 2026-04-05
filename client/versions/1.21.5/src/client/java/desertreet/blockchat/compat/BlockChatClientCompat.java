package desertreet.blockchat.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.PlayerSkin;

import java.util.UUID;

public final class BlockChatClientCompat {

	private BlockChatClientCompat() {
	}

	public static String gameProfileName(Object profile) {
		return profile instanceof GameProfile gameProfile ? gameProfile.getName() : "";
	}

	public static UUID gameProfileId(Object profile) {
		return profile instanceof GameProfile gameProfile ? gameProfile.getId() : null;
	}

	public static long windowHandle(Object window) {
		return window instanceof Window clientWindow ? clientWindow.getWindow() : 0L;
	}

	public static boolean drawPlayerFace(GuiGraphics graphics, Object liveSkin, int x, int y, int size) {
		if (graphics == null || !(liveSkin instanceof PlayerSkin playerSkin)) {
			return false;
		}
		PlayerFaceRenderer.draw(graphics, playerSkin, x, y, size);
		return true;
	}
}
