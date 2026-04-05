package net.minecraft.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

public final class PlayerFaceRenderer {

	private static final int SKIN_TEXTURE_SIZE = 64;
	private static final int FACE_U = 8;
	private static final int FACE_V = 8;
	private static final int HAT_U = 40;
	private static final int HAT_V = 8;

	private PlayerFaceRenderer() {
	}

	public static void draw(GuiGraphics graphics, PlayerSkin skin, int x, int y, int size) {
		if (graphics == null || skin == null || skin.body() == null) {
			return;
		}
		Identifier texture = skin.body().texturePath();
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, FACE_U, FACE_V, size, size, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, HAT_U, HAT_V, size, size, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
	}
}
