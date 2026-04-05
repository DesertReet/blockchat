package net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

public final class GuiGraphics {

	private final GuiGraphicsExtractor delegate;

	public GuiGraphics(Minecraft minecraft, GuiRenderState guiRenderState, int mouseX, int mouseY) {
		this(new GuiGraphicsExtractor(minecraft, guiRenderState, mouseX, mouseY));
	}

	public GuiGraphics(GuiGraphicsExtractor delegate) {
		this.delegate = delegate;
	}

	public GuiGraphicsExtractor unwrap() {
		return delegate;
	}

	public int guiWidth() {
		return delegate.guiWidth();
	}

	public int guiHeight() {
		return delegate.guiHeight();
	}

	public Matrix3x2fStack pose() {
		return delegate.pose();
	}

	public void enableScissor(int minX, int minY, int maxX, int maxY) {
		delegate.enableScissor(minX, minY, maxX, maxY);
	}

	public void disableScissor() {
		delegate.disableScissor();
	}

	public void fill(int minX, int minY, int maxX, int maxY, int color) {
		delegate.fill(minX, minY, maxX, maxY, color);
	}

	public void fillGradient(int minX, int minY, int maxX, int maxY, int topColor, int bottomColor) {
		delegate.fillGradient(minX, minY, maxX, maxY, topColor, bottomColor);
	}

	public int drawString(Font font, String text, int x, int y, int color) {
		delegate.text(font, text, x, y, color);
		return x + font.width(text);
	}

	public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
		delegate.text(font, text, x, y, color, shadow);
		return x + font.width(text);
	}

	public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
		delegate.text(font, text, x, y, color, shadow);
		return x + font.width(text.getString());
	}

	public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		delegate.text(font, text, x, y, color, shadow);
		return x + font.width(text);
	}

	public int drawCenteredString(Font font, String text, int centerX, int y, int color) {
		return drawString(font, text, centerX - font.width(text) / 2, y, color, false);
	}

	public int drawCenteredString(Font font, Component text, int centerX, int y, int color) {
		return drawString(font, text, centerX - font.width(text.getString()) / 2, y, color, false);
	}

	public int drawCenteredString(Font font, FormattedCharSequence text, int centerX, int y, int color) {
		return drawString(font, text, centerX - font.width(text) / 2, y, color, false);
	}

	public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
		delegate.blitSprite(pipeline, sprite, x, y, width, height);
	}

	public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
		delegate.blit(pipeline, texture, x, y, (float) u, (float) v, width, height, textureWidth, textureHeight);
	}

	public void blit(
		RenderPipeline pipeline,
		Identifier texture,
		int x,
		int y,
		float u,
		float v,
		int width,
		int height,
		int textureWidth,
		int textureHeight,
		int regionWidth,
		int regionHeight
	) {
		delegate.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, regionWidth, regionHeight);
	}

	public void blit(
		RenderPipeline pipeline,
		Identifier texture,
		int x,
		int y,
		float u,
		float v,
		int width,
		int height,
		int textureWidth,
		int textureHeight,
		int regionWidth,
		int regionHeight,
		int color
	) {
		delegate.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, regionWidth, regionHeight, color);
	}

	public void renderItem(ItemStack stack, int x, int y) {
		delegate.item(stack, x, y);
	}

	public void setTooltipForNextFrame(Font font, Component component, int mouseX, int mouseY) {
		delegate.setTooltipForNextFrame(font, component, mouseX, mouseY);
	}
}
