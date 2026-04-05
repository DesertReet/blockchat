package desertreet.blockchat.hud;

import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.compat.BlockChatGuiCompat;
import desertreet.blockchat.skin.SkinHelper;
import desertreet.blockchat.util.BlockChatPlayerUuid;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.resources.ResourceLocation;

abstract class FriendProfileToastBase implements Toast {
	private static final ResourceLocation BACKGROUND_SPRITE = ResourceLocation.withDefaultNamespace("toast/system");
	private static final long DISPLAY_TIME_MS = 5_000L;
	private static final int TOAST_WIDTH = 160;
	private static final int TOAST_HEIGHT = Toast.SLOT_HEIGHT;
	private static final int FACE_SIZE = 16;
	private static final int ICON_X = 6;
	private static final int ICON_Y = 8;
	private static final int TEXT_X = 28;
	private static final int TITLE_COLOR = -256;
	private static final int BODY_COLOR = -1;
	private static final int BORDER_THICKNESS = 2;

	private final String title;
	private final String subtitle;
	private final String uuid;
	private final String skinUrl;
	private final int borderColor;
	private final long token;

	protected FriendProfileToastBase(String title, String subtitle, String uuid, String skinUrl) {
		this(title, subtitle, uuid, skinUrl, -1);
	}

	protected FriendProfileToastBase(String title, String subtitle, String uuid, String skinUrl, int borderColor) {
		this.title = title == null || title.isBlank() ? BlockChatStrings.TOAST_FALLBACK_TITLE : title;
		String canonicalUuid = BlockChatPlayerUuid.canonicalize(uuid);
		this.uuid = canonicalUuid != null ? canonicalUuid : "";
		this.subtitle = subtitle == null || subtitle.isBlank() ? BlockChatStrings.TOAST_FALLBACK_SUBTITLE : subtitle;
		this.skinUrl = skinUrl == null || skinUrl.isBlank() ? null : skinUrl;
		this.borderColor = borderColor;
		this.token = BlockChatToastTokens.nextMonotonicToken();
	}

	protected final Visibility renderToast(GuiGraphics graphics, Font font, long fullyVisibleFor) {
		BlockChatGuiCompat.blitSprite(graphics, BACKGROUND_SPRITE, 0, 0, TOAST_WIDTH, TOAST_HEIGHT);
		renderIcon(graphics);
		int availableWidth = TOAST_WIDTH - TEXT_X - 6;
		graphics.drawString(font, title, TEXT_X, 7, TITLE_COLOR, false);
		graphics.drawString(font, trimToWidth(font, subtitle, availableWidth), TEXT_X, 18, BODY_COLOR, false);
		return fullyVisibleFor >= DISPLAY_TIME_MS ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public Object getToken() {
		return Long.valueOf(token);
	}

	@Override
	public int width() {
		return TOAST_WIDTH;
	}

	@Override
	public int height() {
		return TOAST_HEIGHT;
	}

	private void renderIcon(GuiGraphics graphics) {
		if (borderColor != -1) {
			int b = BORDER_THICKNESS;
			graphics.fill(ICON_X - b, ICON_Y - b, ICON_X + FACE_SIZE + b, ICON_Y, borderColor);
			graphics.fill(ICON_X - b, ICON_Y + FACE_SIZE, ICON_X + FACE_SIZE + b, ICON_Y + FACE_SIZE + b, borderColor);
			graphics.fill(ICON_X - b, ICON_Y, ICON_X, ICON_Y + FACE_SIZE, borderColor);
			graphics.fill(ICON_X + FACE_SIZE, ICON_Y, ICON_X + FACE_SIZE + b, ICON_Y + FACE_SIZE, borderColor);
		}
		ResourceLocation faceTexture = SkinHelper.getFaceTexture(skinUrl, uuid);
		if (faceTexture != null) {
			BlockChatGuiCompat.blitTexture(graphics, faceTexture, ICON_X, ICON_Y, 0, 0, FACE_SIZE, FACE_SIZE, FACE_SIZE, FACE_SIZE);
		}
	}

	private static String trimToWidth(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}
}
