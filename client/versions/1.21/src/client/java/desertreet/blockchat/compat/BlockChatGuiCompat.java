package desertreet.blockchat.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class BlockChatGuiCompat {

	private BlockChatGuiCompat() {
	}

	public static void blitSprite(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		graphics.blitSprite(sprite, x, y, width, height);
	}

	public static void blitTexture(
		GuiGraphics graphics,
		ResourceLocation texture,
		int x,
		int y,
		int u,
		int v,
		int width,
		int height,
		int textureWidth,
		int textureHeight
	) {
		graphics.blit(texture, x, y, width, height, (float) u, (float) v, textureWidth, textureHeight, textureWidth, textureHeight);
	}

	public static void setTooltip(GuiGraphics graphics, Font font, Component tooltip, int mouseX, int mouseY) {
		if (tooltip == null) {
			return;
		}
		graphics.renderTooltip(font, tooltip, mouseX, mouseY);
	}
}
