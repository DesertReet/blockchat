package desertreet.blockchat.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.capture.FFmpegLocator;
import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.capture.VideoPreviewPlayer;
import desertreet.blockchat.compat.BlockChatGuiCompat;
import desertreet.blockchat.compat.BlockChatWidgetRenderers;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

public final class DraftComposerWidget {

	private static int composerTextureCounter;

	/** Preview height at the old {@code height/2} layout when composer was capped at 360px. */
	private static final int BASELINE_PREVIEW_INNER = 360 / 2 - 8 - 12;
	/**
	 * Vertical space from composer top excluding the preview slot: type label offset (20),
	 * gap under preview (8), caption (20), bottom button row (32).
	 */
	private static final int ABOVE_PREVIEW_HEADER = 8 + 12;
	private static final int BELOW_PREVIEW_MIN = 8 + 20 + 32;

	private static final int CAPTION_OVERLAY_MAX_LINES = 3;

	private final int x, y, width, height;
	private final PendingCapture.PendingMedia media;
	private final Runnable onDismiss;
	private final Runnable onChooseRecipients;

	private DynamicTexture previewTexture;
	private Identifier previewTextureId;
	private int previewImgWidth;
	private int previewImgHeight;

	private VideoPreviewPlayer videoPlayer;

	private EditBox captionBox;
	private Button sendButton;
	private Button closeButton;

	/** Vertical offset of the caption bar from its default centered position on the media (pixels). */
	private double captionVerticalOffsetPx;
	private boolean captionDragging;

	private record MediaDrawRect(int drawX, int drawY, int drawW, int drawH) {
	}

	/** Inner preview height for a given outer composer height (same rules as {@link #previewSlotHeight()}). */
	public static int previewSlotHeightForComposerHeight(int composerHeight) {
		int doubled = 2 * BASELINE_PREVIEW_INNER;
		int maxFit = composerHeight - ABOVE_PREVIEW_HEADER - BELOW_PREVIEW_MIN;
		if (maxFit < 1) {
			return 1;
		}
		return Math.min(maxFit, doubled);
	}

	/**
	 * Outer width so a landscape preview can use the full preview slot height (width-limited case).
	 * Falls back to 16:9 if media dimensions are unknown.
	 */
	public static int suggestComposerWidth(int composerHeight, int mediaW, int mediaH, int maxOuterWidth) {
		int padding = 8;
		int ph = previewSlotHeightForComposerHeight(composerHeight);
		int mw = mediaW;
		int mh = mediaH;
		if (mw < 1 || mh < 1) {
			mw = 16;
			mh = 9;
		}
		int minInnerW = (ph * mw + mh - 1) / mh;
		int outerW = minInnerW + 2 * padding;
		return Math.min(maxOuterWidth, Math.max(320, outerW));
	}

	public DraftComposerWidget(
		int x,
		int y,
		int width,
		int height,
		PendingCapture.PendingMedia media,
		Runnable onDismiss,
		Runnable onChooseRecipients
	) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.media = media;
		this.onDismiss = onDismiss;
		this.onChooseRecipients = onChooseRecipients;
	}

	public void init() {
		if (media.type() == PendingCapture.MediaType.IMAGE) {
			loadPreviewTexture();
		} else if (media.type() == PendingCapture.MediaType.VIDEO
			&& FFmpegLocator.isAvailable()
			&& isVideoPreviewFileReady()) {
			restartVideoPreviewPlayer();
		}

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		int padding = 8;
		int innerWidth = width - padding * 2;

		int previewAreaHeight = previewSlotHeight();
		int previewAreaY = y + padding + 12;
		int controlsY = previewAreaY + previewAreaHeight + padding;

		captionBox = new EditBox(font, x + padding, controlsY, innerWidth, 20, Component.literal(BlockChatStrings.CAPTION));
		captionBox.setMaxLength(200);
		captionBox.setHint(Component.literal(BlockChatStrings.ADD_A_CAPTION));

		int bottomY = y + height - 24 - padding;
		sendButton = Button.builder(
			Component.literal(BlockChatStrings.SEND),
			b -> onSend()
		).bounds(x + padding, bottomY, innerWidth, 20).build();
		updateSendButtonState();

		closeButton = Button.builder(
			Component.literal(BlockChatStrings.CLOSE_BUTTON),
			b -> onDismiss.run()
		).bounds(x + 4, y + 4, 16, 16).build();
	}

	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(x, y, x + width, y + height, 0xEE1A1A2E);

		int borderColor = 0xFF4A4A6A;
		graphics.fill(x, y, x + width, y + 1, borderColor);
		graphics.fill(x, y + height - 1, x + width, y + height, borderColor);
		graphics.fill(x, y, x + 1, y + height, borderColor);
		graphics.fill(x + width - 1, y, x + width, y + height, borderColor);

		Minecraft client = Minecraft.getInstance();
		int padding = 8;

		graphics.drawCenteredString(client.font, BlockChatStrings.SEND_BLOCK, x + width / 2, y + padding, 0xFFFFFF);

		int previewAreaY = y + padding + 12;
		int previewAreaHeight = previewSlotHeight();
		syncVideoPreviewPlayer();
		updateSendButtonState();
		renderPreview(graphics, x + padding, previewAreaY, width - padding * 2, previewAreaHeight);

		BlockChatWidgetRenderers.render(closeButton, graphics, mouseX, mouseY, partialTick);
		BlockChatWidgetRenderers.render(captionBox, graphics, mouseX, mouseY, partialTick);
		BlockChatWidgetRenderers.render(sendButton, graphics, mouseX, mouseY, partialTick);
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		updateSendButtonState();
		if (tryBeginCaptionDrag(event)) {
			return true;
		}
		if (closeButton.mouseClicked(event, fromSelf)) return true;
		if (captionBox.mouseClicked(event, fromSelf)) {
			captionBox.setFocused(true);
			return true;
		}
		if (sendButton.mouseClicked(event, fromSelf)) return true;
		if (captionBox != null) {
			captionBox.setFocused(false);
		}
		double mx = event.x();
		double my = event.y();
		return mx >= x && mx <= x + width && my >= y && my <= y + height;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!captionDragging) {
			return false;
		}
		if (event.button() != 0) {
			return false;
		}
		MediaDrawRect r = computeMediaDrawRect();
		if (r == null) {
			captionDragging = false;
			return false;
		}
		Font font = Minecraft.getInstance().font;
		int barH = captionBarHeight(font, r.drawW(), r.drawH());
		if (barH < 1) {
			captionDragging = false;
			return false;
		}
		if (!isInsideMediaRect(r, event.x(), event.y())) {
			return false;
		}
		// Drag deltas can be fractional on trackpads/high-DPI input; preserve them until draw time.
		captionVerticalOffsetPx += dragY;
		clampCaptionVerticalOffset(r.drawY(), r.drawH(), barH);
		return true;
	}

	public void mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0) {
			captionDragging = false;
		}
	}

	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			return false;
		}
		if (captionBox == null) {
			return false;
		}
		return captionBox.keyPressed(event);
	}

	public boolean blocksOpenKeyClose() {
		return captionBox != null && captionBox.isFocused();
	}

	public boolean charTyped(CharacterEvent event) {
		return captionBox != null && captionBox.charTyped(event);
	}

	public void onClose() {
		cleanupTexture();
		if (videoPlayer != null) {
			videoPlayer.stop();
			videoPlayer = null;
		}
	}

	private void syncVideoPreviewPlayer() {
		if (media.type() != PendingCapture.MediaType.VIDEO || !FFmpegLocator.isAvailable()) {
			return;
		}
		if (videoPlayer == null) {
			if (!isVideoPreviewFileReady()) {
				return;
			}
			restartVideoPreviewPlayer();
		}
		if (videoPlayer != null) {
			videoPlayer.setExternalAudioTracks(PendingCapture.getClipAudioTracks());
			videoPlayer.setAudioEnabled(true);
			videoPlayer.pump();
		}
	}

	private void restartVideoPreviewPlayer() {
		if (videoPlayer != null) {
			videoPlayer.stop();
			videoPlayer = null;
		}
		if (!isVideoPreviewFileReady()) {
			return;
		}
		int decodeWidth = media.videoWidth();
		int decodeHeight = media.videoHeight();
		if (decodeWidth <= 0 || decodeHeight <= 0) {
			BlockChatClientMod.LOGGER.warn("Pending clip missing recorded dimensions; preview disabled");
			return;
		}
		videoPlayer = new VideoPreviewPlayer(
			media.path(),
			decodeWidth,
			decodeHeight,
			PendingCapture.getClipAudioTracks(),
			true
		);
		videoPlayer.start();
	}

	private void renderPreview(GuiGraphics graphics, int px, int py, int pw, int ph) {
		if (pw < 1 || ph < 1) {
			return;
		}

		MediaDrawRect rect = computeMediaDrawRect();
		if (rect == null) {
			graphics.fill(px, py, px + pw, py + ph, 0xFF333355);
			Minecraft client = Minecraft.getInstance();
			String placeholder;
			if (media.type() == PendingCapture.MediaType.VIDEO) {
				placeholder = !isVideoPreviewFileReady()
					? BlockChatStrings.GENERIC_LOADING
					: FFmpegLocator.isAvailable() ? BlockChatStrings.GENERIC_LOADING : BlockChatStrings.FFMPEG_NOT_FOUND;
			} else {
				placeholder = BlockChatStrings.PREVIEW_NOT_AVAILABLE;
			}
			int tw = client.font.width(placeholder);
			graphics.drawString(client.font, placeholder,
				px + (pw - tw) / 2, py + ph / 2 - 4, 0xFF888888);
			return;
		}

		Identifier texId = media.type() == PendingCapture.MediaType.IMAGE
			? previewTextureId
			: videoPlayer.getTextureId();
		int texW = media.type() == PendingCapture.MediaType.IMAGE ? previewImgWidth : videoPlayer.getFrameWidth();
		int texH = media.type() == PendingCapture.MediaType.IMAGE ? previewImgHeight : videoPlayer.getFrameHeight();

		BlockChatGuiCompat.blitTexture(
			graphics,
			texId,
			rect.drawX,
			rect.drawY,
			0,
			0,
			rect.drawW,
			rect.drawH,
			texW,
			texH
		);
		renderCaptionOverlay(graphics, Minecraft.getInstance().font, rect.drawX, rect.drawY, rect.drawW, rect.drawH);
	}

	private MediaDrawRect computeMediaDrawRect() {
		int padding = 8;
		int previewAreaY = y + padding + 12;
		int ph = previewSlotHeight();
		int pw = width - padding * 2;
		int px = x + padding;
		int py = previewAreaY;
		if (pw < 1 || ph < 1) {
			return null;
		}

		int texW = 0;
		int texH = 0;
		if (media.type() == PendingCapture.MediaType.IMAGE
			&& previewTextureId != null && previewImgWidth > 0 && previewImgHeight > 0) {
			texW = previewImgWidth;
			texH = previewImgHeight;
		} else if (videoPlayer != null && videoPlayer.hasFrame() && videoPlayer.getTextureId() != null) {
			texW = videoPlayer.getFrameWidth();
			texH = videoPlayer.getFrameHeight();
		}
		if (texW < 1 || texH < 1) {
			return null;
		}

		float imgAspect = (float) texW / texH;
		float areaAspect = (float) pw / ph;

		int drawW;
		int drawH;
		if (imgAspect > areaAspect) {
			drawW = pw;
			drawH = (int) (pw / imgAspect);
		} else {
			drawH = ph;
			drawW = (int) (ph * imgAspect);
		}

		int drawX = px + (pw - drawW) / 2;
		int drawY = py + (ph - drawH) / 2;
		return new MediaDrawRect(drawX, drawY, drawW, drawH);
	}

	private boolean isVideoPreviewFileReady() {
		return media.type() == PendingCapture.MediaType.VIDEO && Files.isRegularFile(media.path());
	}

	private boolean tryBeginCaptionDrag(MouseButtonEvent event) {
		if (event.button() != 0) {
			return false;
		}
		MediaDrawRect r = computeMediaDrawRect();
		if (r == null) {
			return false;
		}
		Font font = Minecraft.getInstance().font;
		int barH = captionBarHeight(font, r.drawW(), r.drawH());
		if (barH < 1) {
			return false;
		}
		clampCaptionVerticalOffset(r.drawY(), r.drawH(), barH);
		int centerBarY = r.drawY() + (r.drawH() - barH) / 2;
		int barY = Mth.floor(centerBarY + captionVerticalOffsetPx);
		double mx = event.x();
		double my = event.y();
		if (mx >= r.drawX() && mx < r.drawX() + r.drawW() && my >= barY && my < barY + barH) {
			captionDragging = true;
			return true;
		}
		return false;
	}

	private boolean isInsideMediaRect(MediaDrawRect rect, double mouseX, double mouseY) {
		return mouseX >= rect.drawX() && mouseX < rect.drawX() + rect.drawW()
			&& mouseY >= rect.drawY() && mouseY < rect.drawY() + rect.drawH();
	}

	private int captionBarHeight(Font font, int drawW, int drawH) {
		if (captionBox == null || drawW < 1 || drawH < 1) {
			return 0;
		}
		String raw = captionBox.getValue();
		if (raw == null || raw.isBlank()) {
			return 0;
		}
		int splitMaxW = Math.max(8, (int) Math.ceil((drawW - 12) / 0.75f));
		List<FormattedCharSequence> wrapped = font.split(Component.literal(raw.trim()), splitMaxW);
		if (wrapped.isEmpty()) {
			return 0;
		}
		int barPadV = 2;
		int linePixelH = Math.max(6, (int) Math.ceil(font.lineHeight * 0.75f));
		int maxLinesByHeight = Math.max(1, (drawH - barPadV * 2) / linePixelH);
		int lineCount = Math.min(wrapped.size(), Math.min(maxLinesByHeight, CAPTION_OVERLAY_MAX_LINES));
		int textBlockH = lineCount * linePixelH;
		return Math.min(barPadV * 2 + textBlockH, drawH);
	}

	private void clampCaptionVerticalOffset(int drawY, int drawH, int barH) {
		if (barH >= drawH) {
			captionVerticalOffsetPx = 0.0;
			return;
		}
		int centerBarY = drawY + (drawH - barH) / 2;
		double minOffset = drawY - centerBarY;
		double maxOffset = drawY + drawH - barH - centerBarY;
		captionVerticalOffsetPx = Mth.clamp(captionVerticalOffsetPx, minOffset, maxOffset);
	}

	/**
	 * Target ~2× the historical preview height ({@link #BASELINE_PREVIEW_INNER}), never larger than the panel allows.
	 */
	private int previewSlotHeight() {
		return previewSlotHeightForComposerHeight(height);
	}

	private void renderCaptionOverlay(GuiGraphics graphics, Font font, int drawX, int drawY, int drawW, int drawH) {
		if (drawW < 1 || drawH < 1) {
			return;
		}
		if (captionBox == null) {
			return;
		}
		String raw = captionBox.getValue();
		if (raw == null || raw.isBlank()) {
			return;
		}

		int splitMaxW = Math.max(8, (int) Math.ceil((drawW - 12) / 0.75f));
		String full = raw.trim();
		List<FormattedCharSequence> wrapped = font.split(Component.literal(full), splitMaxW);
		if (wrapped.isEmpty()) {
			return;
		}

		int barPadV = 2;
		int linePixelH = Math.max(6, (int) Math.ceil(font.lineHeight * 0.75f));
		int maxLinesByHeight = Math.max(1, (drawH - barPadV * 2) / linePixelH);
		int lineCount = Math.min(wrapped.size(), Math.min(maxLinesByHeight, CAPTION_OVERLAY_MAX_LINES));
		int textBlockH = lineCount * linePixelH;
		int barH = Math.min(barPadV * 2 + textBlockH, drawH);
		clampCaptionVerticalOffset(drawY, drawH, barH);
		int centerBarY = drawY + (drawH - barH) / 2;
		int barY = Mth.floor(centerBarY + captionVerticalOffsetPx);
		graphics.fill(drawX, barY, drawX + drawW, barY + barH, 0x80000000);

		int textY = barY + barPadV;
		for (int i = 0; i < lineCount; i++) {
			FormattedCharSequence line = wrapped.get(i);
			float lineVisualW = font.width(line) * 0.75f;
			int textX = drawX + Math.round((drawW - lineVisualW) / 2f);
			graphics.pose().pushMatrix();
			graphics.pose().translate(textX, textY);
			graphics.pose().scale(0.75f, 0.75f);
			graphics.drawString(font, line, 0, 0, 0xFFFFFFFF, false);
			graphics.pose().popMatrix();
			textY += linePixelH;
		}
	}

	private void loadPreviewTexture() {
		if (media.type() != PendingCapture.MediaType.IMAGE) {
			return;
		}

		try (InputStream is = new FileInputStream(media.path().toFile())) {
			NativeImage image = NativeImage.read(is);
			previewImgWidth = image.getWidth();
			previewImgHeight = image.getHeight();
			previewTexture = new DynamicTexture(() -> "blockchat_composer_preview", image);
			previewTextureId = Identifier.fromNamespaceAndPath(
				"desertreet", "blockchat_composer_" + (composerTextureCounter++)
			);
			Minecraft.getInstance().getTextureManager().register(previewTextureId, previewTexture);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to load preview texture", e);
		}
	}

	private void onSend() {
		if (onChooseRecipients != null) {
			onChooseRecipients.run();
		}
	}

	private void updateSendButtonState() {
		if (sendButton == null) {
			return;
		}
		sendButton.active = isSendButtonEnabled();
	}

	private boolean isSendButtonEnabled() {
		if (media.type() == PendingCapture.MediaType.IMAGE) {
			return true;
		}
		return media.type() == PendingCapture.MediaType.VIDEO
			&& videoPlayer != null
			&& videoPlayer.hasFrame();
	}

	public PendingCapture.PendingMedia media() {
		return media;
	}

	public String captionText() {
		if (captionBox == null) {
			return "";
		}
		String value = captionBox.getValue();
		return value == null ? "" : value.trim();
	}

	public double captionOffsetY() {
		MediaDrawRect rect = computeMediaDrawRect();
		if (rect == null) {
			return 0.5;
		}
		Font font = Minecraft.getInstance().font;
		int barHeight = captionBarHeight(font, rect.drawW(), rect.drawH());
		if (barHeight < 1) {
			return 0.5;
		}
		clampCaptionVerticalOffset(rect.drawY(), rect.drawH(), barHeight);
		int centerBarY = rect.drawY() + (rect.drawH() - barHeight) / 2;
		double barTop = centerBarY + captionVerticalOffsetPx;
		double range = Math.max(1.0, rect.drawH() - barHeight);
		double normalized = (barTop - rect.drawY()) / range;
		return Mth.clamp(normalized, 0.0, 1.0);
	}

	public long expiryMs() {
		return 0L;
	}

	private void cleanupTexture() {
		if (previewTextureId != null) {
			Minecraft.getInstance().getTextureManager().release(previewTextureId);
			previewTextureId = null;
		}
		if (previewTexture != null) {
			previewTexture.close();
			previewTexture = null;
		}
	}
}
