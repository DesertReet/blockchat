package desertreet.blockchat.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.capture.FFmpegLocator;
import desertreet.blockchat.capture.InboxVideoDecodeDimensions;
import desertreet.blockchat.capture.VideoPreviewPlayer;
import desertreet.blockchat.chat.BlockChatInboxState;

import net.minecraft.client.input.MouseButtonEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class InboxSnapViewerWidget {

	private static final int CAPTION_MAX_LINES = 3;
	private static int textureCounter;

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final BlockChatInboxState.ViewedSnap viewedSnap;
	private final Runnable onAdvance;
	private final Runnable onClose;

	private DynamicTexture imageTexture;
	private Identifier imageTextureId;
	private int imageWidth;
	private int imageHeight;
	private VideoPreviewPlayer videoPlayer;

	private record MediaDrawRect(int drawX, int drawY, int drawW, int drawH) {
	}

	public InboxSnapViewerWidget(
		int x,
		int y,
		int width,
		int height,
		BlockChatInboxState.ViewedSnap viewedSnap,
		Runnable onAdvance,
		Runnable onClose
	) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.viewedSnap = viewedSnap;
		this.onAdvance = onAdvance;
		this.onClose = onClose;
	}

	public void init() {
		if (viewedSnap == null || viewedSnap.snap() == null) {
			return;
		}
		BlockChatInboxState.InboxSnap snap = viewedSnap.snap();
		if (snap.mediaType() == desertreet.blockchat.chat.BlockChatChatState.MediaType.VIDEO) {
			initVideoPlayer(snap);
			return;
		}
		loadImageTexture();
	}

	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(x, y, x + width, y + height, 0xEE101020);
		int border = 0xFF45456A;
		graphics.fill(x, y, x + width, y + 1, border);
		graphics.fill(x, y + height - 1, x + width, y + height, border);
		graphics.fill(x, y, x + 1, y + height, border);
		graphics.fill(x + width - 1, y, x + width, y + height, border);

		Font font = Minecraft.getInstance().font;

		if (videoPlayer != null) {
			videoPlayer.pump();
		}

		MediaDrawRect rect = computeMediaDrawRect();
		if (rect == null) {
			renderLoading(graphics, font);
			return;
		}

		if (!isMediaVisible()) {
			renderLoading(graphics, font);
			return;
		}

		Identifier textureId = textureId();
		int textureWidth = textureWidth();
		int textureHeight = textureHeight();
		if (textureId == null || textureWidth < 1 || textureHeight < 1) {
			renderLoading(graphics, font);
			return;
		}

		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			textureId,
			rect.drawX(),
			rect.drawY(),
			0f,
			0f,
			rect.drawW(),
			rect.drawH(),
			textureWidth,
			textureHeight,
			textureWidth,
			textureHeight,
			0xFFFFFFFF
		);
		if (videoPlayer == null || videoPlayer.hasFrame()) {
			renderCaptionOverlay(graphics, font, rect);
		}
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		MediaDrawRect rect = computeMediaDrawRect();
		if (rect != null && contains(rect, event.x(), event.y())) {
			if (onAdvance != null) {
				onAdvance.run();
			}
			return true;
		}
		if (event.x() >= x && event.x() <= x + width && event.y() >= y && event.y() <= y + height) {
			if (onClose != null) {
				onClose.run();
			}
			return true;
		}
		return false;
	}

	public String senderUuid() {
		return viewedSnap == null ? null : viewedSnap.senderUuid();
	}

	public void onClose() {
		if (videoPlayer != null) {
			videoPlayer.stop();
			videoPlayer = null;
		}
		if (imageTextureId != null) {
			Minecraft.getInstance().getTextureManager().release(imageTextureId);
			imageTextureId = null;
		}
		if (imageTexture != null) {
			imageTexture.close();
			imageTexture = null;
		}
		Path path = viewedSnap == null ? null : viewedSnap.localPath();
		if (path != null) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}

	private void initVideoPlayer(BlockChatInboxState.InboxSnap snap) {
		if (!FFmpegLocator.isAvailable() || viewedSnap.localPath() == null || !Files.isRegularFile(viewedSnap.localPath())) {
			return;
		}
		int[] decode = InboxVideoDecodeDimensions.resolve(snap.mediaWidth(), snap.mediaHeight());
		videoPlayer = new VideoPreviewPlayer(
			viewedSnap.localPath(),
			decode[0],
			decode[1],
			List.of(),
			true
		);
		videoPlayer.start();
	}

	private void loadImageTexture() {
		Path path = viewedSnap == null ? null : viewedSnap.localPath();
		if (path == null || !Files.isRegularFile(path)) {
			return;
		}
		try (InputStream is = Files.newInputStream(path)) {
			NativeImage image = NativeImage.read(is);
			imageWidth = image.getWidth();
			imageHeight = image.getHeight();
			imageTexture = new DynamicTexture(() -> "blockchat_inbox_preview", image);
			imageTextureId = Identifier.fromNamespaceAndPath(
				"desertreet",
				"blockchat_inbox_" + textureCounter++
			);
			Minecraft.getInstance().getTextureManager().register(imageTextureId, imageTexture);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to load inbox snap image preview", e);
		}
	}

	private void renderLoading(GuiGraphics graphics, Font font) {
		int px = x + MEDIA_PAD;
		int py = y + MEDIA_PAD;
		int pw = width - MEDIA_PAD * 2;
		int ph = height - MEDIA_PAD * 2;
		graphics.fill(px, py, px + pw, py + ph, 0xFF2D2D40);
		String text = BlockChatStrings.GENERIC_LOADING;
		int tw = font.width(text);
		graphics.drawString(font, text, px + (pw - tw) / 2, py + ph / 2 - font.lineHeight / 2, 0xFFAAAAAA, false);
	}

	private static final int MEDIA_PAD = 4;

	private MediaDrawRect computeMediaDrawRect() {
		int px = x + MEDIA_PAD;
		int py = y + MEDIA_PAD;
		int pw = width - MEDIA_PAD * 2;
		int ph = height - MEDIA_PAD * 2;
		if (pw < 1 || ph < 1) {
			return null;
		}

		int mediaW = textureWidth();
		int mediaH = textureHeight();
		if (mediaW < 1 || mediaH < 1) {
			return null;
		}

		float mediaAspect = (float) mediaW / mediaH;
		float areaAspect = (float) pw / ph;
		int drawW;
		int drawH;
		if (mediaAspect > areaAspect) {
			drawW = pw;
			drawH = Math.max(1, Math.round(pw / mediaAspect));
		} else {
			drawH = ph;
			drawW = Math.max(1, Math.round(ph * mediaAspect));
		}
		int drawX = px + (pw - drawW) / 2;
		int drawY = py + (ph - drawH) / 2;
		return new MediaDrawRect(drawX, drawY, drawW, drawH);
	}

	private boolean contains(MediaDrawRect rect, double mouseX, double mouseY) {
		return mouseX >= rect.drawX() && mouseX < rect.drawX() + rect.drawW()
			&& mouseY >= rect.drawY() && mouseY < rect.drawY() + rect.drawH();
	}

	private Identifier textureId() {
		if (videoPlayer != null) {
			return videoPlayer.getTextureId();
		}
		return imageTextureId;
	}

	private int textureWidth() {
		if (videoPlayer != null) {
			return videoPlayer.getFrameWidth();
		}
		return imageWidth;
	}

	private int textureHeight() {
		if (videoPlayer != null) {
			return videoPlayer.getFrameHeight();
		}
		return imageHeight;
	}

	private boolean isMediaVisible() {
		BlockChatInboxState.InboxSnap snap = viewedSnap == null ? null : viewedSnap.snap();
		if (snap == null) {
			return false;
		}
		if (snap.mediaType() == desertreet.blockchat.chat.BlockChatChatState.MediaType.VIDEO) {
			return videoPlayer != null && videoPlayer.hasFrame();
		}
		return imageTextureId != null && imageWidth > 0 && imageHeight > 0;
	}

	private void renderCaptionOverlay(GuiGraphics graphics, Font font, MediaDrawRect rect) {
		String rawCaption = viewedSnap.snap().captionText();
		if (rawCaption == null || rawCaption.isBlank()) {
			return;
		}

		String caption = rawCaption.trim();
		int splitWidth = Math.max(8, (int) Math.ceil((rect.drawW() - 12) / 0.75f));
		List<FormattedCharSequence> wrapped = font.split(Component.literal(caption), splitWidth);
		if (wrapped.isEmpty()) {
			return;
		}

		int barPaddingVertical = 2;
		int linePixelHeight = Math.max(6, (int) Math.ceil(font.lineHeight * 0.75f));
		int maxLinesByHeight = Math.max(1, (rect.drawH() - barPaddingVertical * 2) / linePixelHeight);
		int lineCount = Math.min(wrapped.size(), Math.min(maxLinesByHeight, CAPTION_MAX_LINES));
		int textBlockHeight = lineCount * linePixelHeight;
		int barHeight = Math.min(barPaddingVertical * 2 + textBlockHeight, rect.drawH());
		int barY = captionBarTop(rect, barHeight, viewedSnap.snap().captionOffsetY());

		graphics.fill(rect.drawX(), barY, rect.drawX() + rect.drawW(), barY + barHeight, 0x80000000);

		int textY = barY + barPaddingVertical;
		for (int i = 0; i < lineCount; i++) {
			FormattedCharSequence line = wrapped.get(i);
			float lineVisualWidth = font.width(line) * 0.75f;
			int textX = rect.drawX() + Math.round((rect.drawW() - lineVisualWidth) / 2f);
			graphics.pose().pushMatrix();
			graphics.pose().translate(textX, textY);
			graphics.pose().scale(0.75f, 0.75f);
			graphics.drawString(font, line, 0, 0, 0xFFFFFFFF, false);
			graphics.pose().popMatrix();
			textY += linePixelHeight;
		}
	}

	private int captionBarTop(MediaDrawRect rect, int barHeight, double offsetY) {
		int range = Math.max(0, rect.drawH() - barHeight);
		double normalized = normalizeOffset(offsetY);
		int top = rect.drawY() + (int) Math.round(range * normalized);
		return Mth.clamp(top, rect.drawY(), rect.drawY() + range);
	}

	private double normalizeOffset(double offsetY) {
		if (Double.isNaN(offsetY) || Double.isInfinite(offsetY)) {
			return 0.5;
		}
		if (offsetY >= 0.0 && offsetY <= 1.0) {
			return offsetY;
		}
		// Compatibility fallback for center-relative payloads.
		double centered = Mth.clamp(offsetY, -1.0, 1.0);
		return Mth.clamp((centered + 1.0) * 0.5, 0.0, 1.0);
	}
}
