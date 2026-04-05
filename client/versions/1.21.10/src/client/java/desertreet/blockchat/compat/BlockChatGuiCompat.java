package desertreet.blockchat.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class BlockChatGuiCompat {

	private BlockChatGuiCompat() {
	}

	public static void blitSprite(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
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
		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			texture,
			x,
			y,
			(float) u,
			(float) v,
			width,
			height,
			textureWidth,
			textureHeight,
			textureWidth,
			textureHeight,
			0xFFFFFFFF
		);
	}

	public static void setTooltip(GuiGraphics graphics, Font font, Component tooltip, int mouseX, int mouseY) {
		if (tooltip == null) {
			return;
		}
		graphics.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
	}
}
