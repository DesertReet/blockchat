package desertreet.blockchat.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.chat.BlockChatChatState;
import desertreet.blockchat.chat.BlockChatInboxState;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;
import desertreet.blockchat.social.BlockChatSocialState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ChatTab implements BlockChatTab {

	private static final int ROW_HEIGHT = 28;
	private static final int FACE_SIZE = 22;
	private static final int OUTER_PADDING = 6;
	private static final int ICON_SIZE = 8;
	private static final int ICON_TEXT_GAP = 3;
	private static final int SCROLL_SPEED = ROW_HEIGHT;

	// Colors
	private static final int RED = 0xFFE84040;
	private static final int PURPLE = 0xFF9B59B6;
	private static final int TEXT_WHITE = 0xFFFFFFFF;
	private static final int TEXT_SUBTITLE = 0xFFCCCCCC;
	private static final int TEXT_TIMESTAMP = 0xFFAAAAAA;
	private static final int TEXT_DARK_GRAY = 0xFF888888;
	private static final int ROW_HOVER = 0x33FFFFFF;
	private static final int DIVIDER = 0x44FFFFFF;

	private int x, y, width, height;
	private int scrollOffset;
	private final List<ChatFriendEntry> entries = new ArrayList<>();
	private final List<RowHitbox> renderedRows = new ArrayList<>();
	private final Consumer<String> onChatRowClicked;
	/** Avoid full list rebuild every tick; still refresh periodically for PlayerLookupCache placeholders. */
	private String lastEntriesSnapshot = "";
	private int entriesRefreshCooldown;

	private static final int PLACEHOLDER_REFRESH_INTERVAL_TICKS = 20;

	public ChatTab() {
		this(uuid -> {
		});
	}

	public ChatTab(Consumer<String> onChatRowClicked) {
		this.onChatRowClicked = onChatRowClicked != null ? onChatRowClicked : uuid -> {
		};
	}

	@Override
	public String getTitle() {
		return BlockChatStrings.CHAT_TAB;
	}

	@Override
	public void init(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.scrollOffset = 0;
		this.entriesRefreshCooldown = 0;
		rebuildEntries();
		lastEntriesSnapshot = computeEntriesSnapshot();
	}

	@Override
	public void tick() {
		entriesRefreshCooldown++;
		String now = computeEntriesSnapshot();
		boolean entriesChanged = !now.equals(lastEntriesSnapshot);
		if (entriesChanged || entriesRefreshCooldown >= PLACEHOLDER_REFRESH_INTERVAL_TICKS) {
			entriesRefreshCooldown = 0;
			rebuildEntries();
			lastEntriesSnapshot = computeEntriesSnapshot();
		}
	}

	private static String computeEntriesSnapshot() {
		StringBuilder sb = new StringBuilder();
		for (BlockChatSocialState.FriendRecord r : BlockChatSocialState.friendsByUuid().values()) {
			if (r.relationship() == BlockChatSocialState.FriendRelationship.FRIENDS) {
				sb.append(r.uuid()).append('\u001f').append(r.username()).append('\u001f').append(String.valueOf(r.skinUrl())).append('\n');
			}
		}
		sb.append("---\n");
		sb.append(BlockChatChatState.snapshotString());
		return sb.toString();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		renderedRows.clear();

		if (entries.isEmpty()) {
			String text = BlockChatStrings.NO_CHATS_YET;
			int textWidth = font.width(text);
			graphics.drawString(font, text, x + (width - textWidth) / 2, y + height / 2 - 4, TEXT_WHITE);
			return;
		}

		graphics.enableScissor(x, y, x + width, y + height);

		int contentLeft = x + OUTER_PADDING;
		int contentRight = x + width - OUTER_PADDING;
		int contentWidth = contentRight - contentLeft;
		int startY = y + OUTER_PADDING - scrollOffset;

		for (int i = 0; i < entries.size(); i++) {
			int rowY = startY + i * ROW_HEIGHT;
			int rowBottom = rowY + ROW_HEIGHT;

			if (rowBottom < y || rowY > y + height) continue;

			ChatFriendEntry entry = entries.get(i);

			// Hover highlight
			if (mouseX >= contentLeft && mouseX < contentRight
				&& mouseY >= rowY && mouseY < rowBottom
				&& mouseY >= y && mouseY < y + height) {
				graphics.fill(contentLeft, rowY, contentRight, rowBottom, ROW_HOVER);
			}

			// Divider line
			if (i < entries.size() - 1) {
				graphics.fill(contentLeft + FACE_SIZE + 10, rowBottom - 1, contentRight, rowBottom, DIVIDER);
			}

			// Face
			int faceX = contentLeft + 2;
			int faceY = rowY + (ROW_HEIGHT - FACE_SIZE) / 2;
			renderFace(graphics, entry, faceX, faceY);

			// Name
			int textX = faceX + FACE_SIZE + 8;
			int nameY = rowY + 4;
			graphics.drawString(font, truncate(font, entry.username, contentWidth - FACE_SIZE - 16), textX, nameY, TEXT_WHITE, false);

			// Status subtitle
			int subtitleY = nameY + font.lineHeight + 2;
			renderStatusLine(graphics, font, entry, textX, subtitleY, contentRight);
			renderedRows.add(new RowHitbox(contentLeft, rowY, contentRight, rowBottom, entry.uuid));
		}

		graphics.disableScissor();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		if (event.button() != 0) {
			return false;
		}
		for (RowHitbox row : renderedRows) {
			if (row.contains(event.x(), event.y())) {
				onChatRowClicked.accept(row.uuid());
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
			int totalContentHeight = entries.size() * ROW_HEIGHT + OUTER_PADDING;
			int maxScroll = Math.max(0, totalContentHeight - height);
			scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * SCROLL_SPEED)));
			return true;
		}
		return false;
	}

	private void renderStatusLine(GuiGraphics graphics, Font font, ChatFriendEntry entry, int drawX, int drawY, int maxX) {
		if (entry.status == null) return;

		boolean isVideo = entry.status.mediaType == MediaType.VIDEO;
		boolean isSent = entry.status.direction == Direction.SENT;

		int iconColor = isVideo ? PURPLE : RED;

		// Draw status icon
		int iconX = drawX;
		int iconY = drawY + (font.lineHeight - ICON_SIZE) / 2;

		if (isSent) {
			drawArrowLeft(graphics, iconX, iconY, iconColor, entry.status.filled);
		} else {
			drawSquareIcon(graphics, iconX, iconY, iconColor, entry.status.filled);
		}

		// Status text
		int textStartX = iconX + ICON_SIZE + ICON_TEXT_GAP;
		String statusText = entry.status.label;
		graphics.drawString(font, statusText, textStartX, drawY, TEXT_SUBTITLE, false);

		// Timestamp
		if (entry.status.timestampMs > 0) {
			String timeAgo = " · " + formatRelativeTime(entry.status.timestampMs);
			int statusTextWidth = font.width(statusText);
			int timeX = textStartX + statusTextWidth;
			if (timeX + font.width(timeAgo) <= maxX) {
				graphics.drawString(font, timeAgo, timeX, drawY, TEXT_TIMESTAMP, false);
			}
		}
	}

	private void drawArrowLeft(GuiGraphics graphics, int x, int y, int color, boolean filled) {
		// Left-pointing arrow: base on the RIGHT (col=size-1, full height), tip on the LEFT (col=0, 1px)
		int size = ICON_SIZE;
		int halfH = size / 2;
		if (filled) {
			for (int col = 0; col < size; col++) {
				// col grows left-to-right; height grows with col so base is on the right
				int h = Math.max(1, (int) Math.round((col + 1.0) / size * halfH));
				// Mirror horizontally: draw at (size-1-col) so the wide part ends up on the left
				int drawCol = size - 1 - col;
				int top = y + halfH - h;
				int bottom = y + halfH + h;
				graphics.fill(x + drawCol, top, x + drawCol + 1, bottom, color);
			}
		} else {
			// Outline: diagonal edges + right vertical edge (base)
			for (int col = 0; col < size; col++) {
				int h = Math.max(1, (int) Math.round((col + 1.0) / size * halfH));
				int drawCol = size - 1 - col;
				int top = y + halfH - h;
				int bottom = y + halfH + h - 1;
				// Top diagonal edge
				graphics.fill(x + drawCol, top, x + drawCol + 1, top + 1, color);
				// Bottom diagonal edge
				if (bottom > top) {
					graphics.fill(x + drawCol, bottom, x + drawCol + 1, bottom + 1, color);
				}
			}
			// Left vertical edge (the base of the triangle - tip points left, base is on left)
			int baseH = halfH;
			int baseTop = y + halfH - baseH;
			int baseBottom = y + halfH + baseH;
			graphics.fill(x, baseTop, x + 1, baseBottom, color);
		}
	}

	private void drawSquareIcon(GuiGraphics graphics, int x, int y, int color, boolean filled) {
		if (filled) {
			graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, color);
		} else {
			graphics.fill(x, y, x + ICON_SIZE, y + 1, color);
			graphics.fill(x, y + ICON_SIZE - 1, x + ICON_SIZE, y + ICON_SIZE, color);
			graphics.fill(x, y, x + 1, y + ICON_SIZE, color);
			graphics.fill(x + ICON_SIZE - 1, y, x + ICON_SIZE, y + ICON_SIZE, color);
		}
	}

	private String formatRelativeTime(long timestampMs) {
		long now = System.currentTimeMillis();
		long diffMs = now - timestampMs;
		if (diffMs < 0) diffMs = 0;

		long seconds = diffMs / 1000;
		if (seconds < 60) return BlockChatStrings.shortSeconds(seconds);
		long minutes = seconds / 60;
		if (minutes < 60) return BlockChatStrings.shortMinutes(minutes);
		long hours = minutes / 60;
		if (hours < 24) return BlockChatStrings.shortHours(hours);
		long days = hours / 24;
		if (days < 7) return BlockChatStrings.shortDays(days);
		long weeks = days / 7;
		return BlockChatStrings.shortWeeks(weeks);
	}

	private void renderFace(GuiGraphics graphics, ChatFriendEntry entry, int drawX, int drawY) {
		if (entry.liveSkin != null) {
			PlayerFaceRenderer.draw(graphics, entry.liveSkin, drawX, drawY, FACE_SIZE);
			return;
		}

		Identifier faceTexture = SkinHelper.getFaceTexture(entry.skinUrl, entry.uuid);
		if (faceTexture != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, faceTexture, drawX, drawY, 0, 0, FACE_SIZE, FACE_SIZE, FACE_SIZE, FACE_SIZE);
			return;
		}

		// Fallback: colored square with initial
		graphics.fill(drawX, drawY, drawX + FACE_SIZE, drawY + FACE_SIZE, 0xFF4C5B75);
		String fallback = entry.username.isBlank() ? "?" : entry.username.substring(0, 1).toUpperCase(Locale.ROOT);
		Font font = Minecraft.getInstance().font;
		int textW = font.width(fallback);
		graphics.drawString(font, fallback, drawX + (FACE_SIZE - textW) / 2, drawY + (FACE_SIZE - font.lineHeight) / 2 + 1, TEXT_WHITE, false);
	}

	private String truncate(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	private void rebuildEntries() {
		entries.clear();

		// Accepted friends always appear, even before they have any recent message activity.
		for (BlockChatSocialState.FriendRecord record : BlockChatSocialState.friendsByUuid().values()) {
			if (record.relationship() == BlockChatSocialState.FriendRelationship.FRIENDS) {
				String skin = PlayerLookupCache.resolveSkinUrl(record.username(), record.uuid(), record.skinUrl());
				entries.add(new ChatFriendEntry(record.uuid(), record.username(), skin, null, statusFor(record.uuid())));
			}
		}

		entries.sort(Comparator
			.<ChatFriendEntry>comparingLong(e -> e.status == null ? Long.MIN_VALUE : e.status.activityTimestampMs).reversed()
			.thenComparing(e -> e.status == null ? Integer.MAX_VALUE : e.status.priorityRank)
			.thenComparing(e -> e.username, String.CASE_INSENSITIVE_ORDER)
		);
	}

	private ChatStatus statusFor(String uuid) {
		BlockChatChatState.RecentContact recent = BlockChatChatState.recentsByUuid().get(uuid);
		return recent == null ? null : statusFor(recent);
	}

	private ChatStatus statusFor(BlockChatChatState.RecentContact recent) {
		if (recent.hasIncomingUnread()) {
			// Use the oldest unread snap's media type from the inbox, falling back to the recents summary
			BlockChatChatState.MediaType oldestMediaType = BlockChatInboxState.oldestUnreadMediaTypeForSender(recent.uuid());
			BlockChatChatState.MediaType effectiveMediaType = oldestMediaType != null ? oldestMediaType : recent.incomingUnopenedMediaType();
			return new ChatStatus(
				Direction.RECEIVED,
				mapMediaType(effectiveMediaType, recent.lastMediaType()),
				true,
				BlockChatStrings.NEW_BLOCK,
				recent.incomingUnopenedTimestampMs() > 0 ? recent.incomingUnopenedTimestampMs() : recent.lastTimestampMs(),
				recent.latestActivityTimestampMs(),
				0
			);
		}
		if (recent.hasOutgoingUnopened()) {
			return new ChatStatus(
				Direction.SENT,
				mapMediaType(recent.outgoingUnopenedMediaType(), recent.lastMediaType()),
				true,
				BlockChatStrings.SENT_BLOCK,
				recent.outgoingUnopenedTimestampMs() > 0 ? recent.outgoingUnopenedTimestampMs() : recent.lastTimestampMs(),
				recent.latestActivityTimestampMs(),
				1
			);
		}
		return new ChatStatus(
			recent.lastDirection() == BlockChatChatState.Direction.SENT ? Direction.SENT : Direction.RECEIVED,
			mapMediaType(recent.lastMediaType(), BlockChatChatState.MediaType.IMAGE),
			false,
			recent.lastDirection() == BlockChatChatState.Direction.SENT ? BlockChatStrings.OPENED_BLOCK : BlockChatStrings.RECEIVED_BLOCK,
			recent.lastTimestampMs(),
			recent.latestActivityTimestampMs(),
			2
		);
	}

	private MediaType mapMediaType(BlockChatChatState.MediaType mediaType, BlockChatChatState.MediaType fallback) {
		BlockChatChatState.MediaType resolved = mediaType != null ? mediaType : fallback;
		return resolved == BlockChatChatState.MediaType.VIDEO ? MediaType.VIDEO : MediaType.IMAGE;
	}

	private record ChatFriendEntry(String uuid, String username, String skinUrl, PlayerSkin liveSkin, ChatStatus status) {
	}

	private record ChatStatus(
		Direction direction,
		MediaType mediaType,
		boolean filled,
		String label,
		long timestampMs,
		long activityTimestampMs,
		int priorityRank
	) {
	}

	private record RowHitbox(int left, int top, int right, int bottom, String uuid) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
		}
	}

	private enum Direction {
		SENT,
		RECEIVED
	}

	private enum MediaType {
		IMAGE,
		VIDEO
	}
}
