package desertreet.blockchat.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.compat.BlockChatGuiCompat;
import desertreet.blockchat.compat.BlockChatLegacyInputs;
import desertreet.blockchat.compat.BlockChatWidgetInputs;
import desertreet.blockchat.compat.BlockChatWidgetRenderers;
import desertreet.blockchat.send.BlockChatSnapSender;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;
import desertreet.blockchat.social.BlockChatSocialState;
import desertreet.blockchat.util.BlockChatPlayerUuid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class SendRecipientsWidget {

	private static final int ROW_HEIGHT = 24;
	private static final int FACE_SIZE = 16;
	private static final int PADDING = 8;
	private static final int MAX_RECIPIENTS = 10;

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final Runnable onClose;
	private final Consumer<List<BlockChatSnapSender.SendTarget>> onSendConfirmed;

	private final Map<String, BlockChatSnapSender.SendTarget> selectedTargets = new LinkedHashMap<>();
	private final List<RowHitbox> renderedRows = new ArrayList<>();

	private EditBox searchBox;
	private Button sendButton;
	private Button closeButton;

	private int scrollOffset;

	public SendRecipientsWidget(
		int x,
		int y,
		int width,
		int height,
		Runnable onClose,
		Consumer<List<BlockChatSnapSender.SendTarget>> onSendConfirmed
	) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.onClose = onClose;
		this.onSendConfirmed = onSendConfirmed;
	}

	public void init() {
		Font font = Minecraft.getInstance().font;
		searchBox = new EditBox(
			font,
			contentLeft(),
			searchBoxY(),
			contentWidth(),
			20,
			Component.literal(BlockChatStrings.SEARCH_USERNAME)
		);
		searchBox.setHint(Component.literal(BlockChatStrings.FILTER_FRIENDS));
		searchBox.setMaxLength(16);
		searchBox.setCanLoseFocus(true);

		sendButton = Button.builder(Component.literal(BlockChatStrings.SEND), button -> onSendConfirmed.accept(selectedTargets()))
			.bounds(contentLeft(), sendButtonY(), contentWidth(), 20)
			.build();
		sendButton.active = false;

		closeButton = Button.builder(Component.literal(BlockChatStrings.CLOSE_BUTTON), button -> onClose.run())
			.bounds(x + 4, y + 4, 16, 16)
			.build();
	}

	public void tick() {
		syncSendButton();
	}

	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderedRows.clear();

		graphics.fill(x, y, x + width, y + height, 0xEE1A1A2E);
		int borderColor = 0xFF4A4A6A;
		graphics.fill(x, y, x + width, y + 1, borderColor);
		graphics.fill(x, y + height - 1, x + width, y + height, borderColor);
		graphics.fill(x, y, x + 1, y + height, borderColor);
		graphics.fill(x + width - 1, y, x + width, y + height, borderColor);

		Font font = Minecraft.getInstance().font;
		graphics.drawCenteredString(font, BlockChatStrings.CHOOSE_RECIPIENTS, x + width / 2, y + PADDING, 0xFFFFFFFF);
		BlockChatWidgetRenderers.render(closeButton, graphics, mouseX, mouseY, partialTick);

		graphics.drawString(font, BlockChatStrings.FILTER_YOUR_FRIENDS_BY_NAME, contentLeft(), sectionTopY(), 0xFFFFFFFF, false);
		BlockChatWidgetRenderers.render(searchBox, graphics, mouseX, mouseY, partialTick);

		int statusY = searchStatusY(font);
		int statusHeight = renderSearchStatus(graphics, font, statusY, mouseX, mouseY, partialTick);
		int listTop = statusY + statusHeight + 4;

		graphics.drawString(font, BlockChatStrings.FRIENDS_HEADER, contentLeft(), listTop, 0xFFFFFFFF, false);
		renderFriendRows(graphics, font, listTop + font.lineHeight + 4, mouseX, mouseY);

		BlockChatWidgetRenderers.render(sendButton, graphics, mouseX, mouseY, partialTick);
	}

	public boolean mouseClicked(BlockChatLegacyInputs.MouseButton event, boolean fromSelf) {
		if (BlockChatWidgetInputs.mouseClicked(closeButton, event, fromSelf)) {
			return true;
		}
		if (BlockChatWidgetInputs.mouseClicked(searchBox, event, fromSelf)) {
			searchBox.setFocused(true);
			return true;
		}
		if (BlockChatWidgetInputs.mouseClicked(sendButton, event, fromSelf)) {
			return true;
		}
		for (RowHitbox row : renderedRows) {
			if (row.contains(event.x(), event.y())) {
				toggleSelection(row.target());
				return true;
			}
		}
		if (searchBox != null) {
			searchBox.setFocused(false);
		}
		return event.x() >= x && event.x() <= x + width && event.y() >= y && event.y() <= y + height;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!isOverList(mouseX, mouseY)) {
			return false;
		}
		int totalContentHeight = Math.max(0, visibleFriendTargets().size() * ROW_HEIGHT);
		int maxScroll = Math.max(0, totalContentHeight - listHeight());
		scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * ROW_HEIGHT)));
		return true;
	}

	public boolean keyPressed(BlockChatLegacyInputs.Key event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			return false;
		}
		if (searchBox != null && BlockChatWidgetInputs.keyPressed(searchBox, event)) {
			return true;
		}
		return false;
	}

	public boolean charTyped(BlockChatLegacyInputs.Character event) {
		return searchBox != null && BlockChatWidgetInputs.charTyped(searchBox, event);
	}

	public boolean blocksOpenKeyClose() {
		return searchBox != null && searchBox.isFocused();
	}

	public void onClose() {
	}

	private void renderFriendRows(GuiGraphics graphics, Font font, int listStartY, int mouseX, int mouseY) {
		List<BlockChatSnapSender.SendTarget> visibleTargets = visibleFriendTargets();
		int listBottom = sendButtonY() - 8;
		int listHeight = Math.max(0, listBottom - listStartY);
		if (listHeight <= 0) {
			return;
		}

		graphics.enableScissor(contentLeft(), listStartY, contentRight(), listBottom);
		int startY = listStartY - scrollOffset;
		for (int i = 0; i < visibleTargets.size(); i++) {
			BlockChatSnapSender.SendTarget target = visibleTargets.get(i);
			int rowY = startY + i * ROW_HEIGHT;
			if (rowY + ROW_HEIGHT < listStartY || rowY > listBottom) {
				continue;
			}
			renderRecipientRow(graphics, font, contentLeft(), rowY, contentWidth(), target, mouseX, mouseY);
		}
		graphics.disableScissor();

		if (visibleTargets.isEmpty()) {
			graphics.drawString(font, BlockChatStrings.NO_MATCHING_FRIENDS, contentLeft(), listStartY + 4, 0xFFDDDDDD, false);
		}
	}

	private void renderRecipientRow(
		GuiGraphics graphics,
		Font font,
		int drawX,
		int drawY,
		int rowWidth,
		BlockChatSnapSender.SendTarget target,
		int mouseX,
		int mouseY
	) {
		boolean hovered = mouseX >= drawX && mouseX < drawX + rowWidth && mouseY >= drawY && mouseY < drawY + ROW_HEIGHT;
		boolean selected = selectedTargets.containsKey(target.uuid());
		int fill = selected ? 0x6645A069 : hovered ? 0x33FFFFFF : 0x22000000;
		graphics.fill(drawX, drawY, drawX + rowWidth, drawY + ROW_HEIGHT - 1, fill);

		int faceX = drawX + 4;
		int faceY = drawY + (ROW_HEIGHT - FACE_SIZE) / 2;
		renderFace(graphics, target, faceX, faceY);

		int textX = faceX + FACE_SIZE + 6;
		graphics.drawString(font, truncate(font, target.username(), rowWidth - 70), textX, drawY + 5, 0xFFFFFFFF, false);

		String action = selected ? BlockChatStrings.SELECTED : BlockChatStrings.SELECT;
		int actionWidth = font.width(action);
		int actionX = drawX + rowWidth - actionWidth - 8;
		int actionColor = selected ? 0xFF9CFFAD : 0xFFFFFFFF;
		graphics.drawString(font, action, actionX, drawY + 5, actionColor, false);

		renderedRows.add(new RowHitbox(drawX, drawY, drawX + rowWidth, drawY + ROW_HEIGHT, target));
	}

	private int renderSearchStatus(GuiGraphics graphics, Font font, int drawY, int mouseX, int mouseY, float partialTick) {
		graphics.drawString(font, BlockChatStrings.TYPE_TO_FILTER_ACCEPTED_FRIENDS, contentLeft(), drawY, 0xFFDDDDDD, false);
		return font.lineHeight;
	}

	private void toggleSelection(BlockChatSnapSender.SendTarget target) {
		String uuid = BlockChatPlayerUuid.canonicalize(target.uuid());
		if (uuid == null) {
			return;
		}
		if (selectedTargets.containsKey(uuid)) {
			selectedTargets.remove(uuid);
			syncSendButton();
			return;
		}
		if (selectedTargets.size() >= MAX_RECIPIENTS) {
			return;
		}
		selectedTargets.put(uuid, new BlockChatSnapSender.SendTarget(uuid, target.username(), target.skinUrl()));
		syncSendButton();
	}

	private void syncSendButton() {
		int count = selectedTargets.size();
		sendButton.active = count > 0;
		if (count <= 1) {
			sendButton.setMessage(Component.literal(BlockChatStrings.SEND));
		} else {
			sendButton.setMessage(Component.literal(BlockChatStrings.sendToCount(count)));
		}
	}

	private List<BlockChatSnapSender.SendTarget> visibleFriendTargets() {
		String filter = normalizedQuery();
		List<BlockChatSnapSender.SendTarget> targets = new ArrayList<>();
		for (BlockChatSocialState.FriendRecord record : BlockChatSocialState.friendsByUuid().values()) {
			if (record.relationship() != BlockChatSocialState.FriendRelationship.FRIENDS) {
				continue;
			}
			String username = record.username();
			if (!filter.isEmpty() && (username == null || !username.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))) {
				continue;
			}
			String skinUrl = PlayerLookupCache.resolveSkinUrl(username, record.uuid(), record.skinUrl());
			targets.add(new BlockChatSnapSender.SendTarget(record.uuid(), username, skinUrl));
		}
		targets.sort(Comparator.comparing(BlockChatSnapSender.SendTarget::username, String.CASE_INSENSITIVE_ORDER));
		return targets;
	}

	private List<BlockChatSnapSender.SendTarget> selectedTargets() {
		return List.copyOf(selectedTargets.values());
	}

	private void renderFace(GuiGraphics graphics, BlockChatSnapSender.SendTarget target, int drawX, int drawY) {
		ResourceLocation faceTexture = SkinHelper.getFaceTexture(target.skinUrl(), target.uuid());
		if (faceTexture != null) {
			BlockChatGuiCompat.blitTexture(graphics, faceTexture, drawX, drawY, 0, 0, FACE_SIZE, FACE_SIZE, FACE_SIZE, FACE_SIZE);
			return;
		}
		graphics.fill(drawX, drawY, drawX + FACE_SIZE, drawY + FACE_SIZE, 0xFF4C5B75);
		String fallback = target.username().isBlank() ? "?" : target.username().substring(0, 1).toUpperCase(Locale.ROOT);
		Font font = Minecraft.getInstance().font;
		int textW = font.width(fallback);
		graphics.drawString(font, fallback, drawX + (FACE_SIZE - textW) / 2, drawY + (FACE_SIZE - font.lineHeight) / 2 + 1, 0xFFFFFFFF, false);
	}

	private String truncate(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	private boolean isOverList(double mouseX, double mouseY) {
		int listStartY = searchStatusY(Minecraft.getInstance().font) + Minecraft.getInstance().font.lineHeight + 4;
		return mouseX >= contentLeft() && mouseX < contentRight() && mouseY >= listStartY && mouseY < sendButtonY() - 8;
	}

	private int sectionTopY() {
		return y + PADDING + 12;
	}

	private int searchBoxY() {
		return sectionTopY() + Minecraft.getInstance().font.lineHeight + 4;
	}

	private int searchStatusY(Font font) {
		return searchBoxY() + 24;
	}

	private int sendButtonY() {
		return y + height - PADDING - 20;
	}

	private int listHeight() {
		int listTop = searchStatusY(Minecraft.getInstance().font) + Minecraft.getInstance().font.lineHeight + 4;
		return Math.max(0, sendButtonY() - 8 - listTop);
	}

	private int contentLeft() {
		return x + PADDING;
	}

	private int contentRight() {
		return x + width - PADDING;
	}

	private int contentWidth() {
		return width - PADDING * 2;
	}

	private String normalizedQuery() {
		return searchBox == null ? "" : searchBox.getValue().trim();
	}

	private record RowHitbox(int left, int top, int right, int bottom, BlockChatSnapSender.SendTarget target) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
		}
	}
}
