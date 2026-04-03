package desertreet.blockchat.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;
import desertreet.blockchat.util.BlockChatPlayerUuid;
import desertreet.blockchat.social.BlockChatSocialState;
import desertreet.blockchat.social.BlockChatSocialState.FriendActionOrigin;
import desertreet.blockchat.social.BlockChatSocialState.FriendRelationship;
import desertreet.blockchat.social.BlockChatSocialState.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class FindFriendsTab implements BlockChatTab {
	private static final int ROW_HEIGHT = 20;
	private static final int FACE_SIZE = 16;
	private static final int FOOTER_HEIGHT = 20;
	private static final int OUTER_PADDING_X = 8;
	private static final int OUTER_PADDING_TOP = 6;
	private static final int SECTION_GAP = 10;
	private static final int LABEL_TO_CONTROL_GAP = 4;
	private static final int CONTROL_TO_STATUS_GAP = 6;
	private static final int SEARCH_BUTTON_WIDTH = 58;
	private static final int SEARCH_ROW_GAP = 4;
	private static final int ACTION_BUTTON_WIDTH = 72;
	private static final int REQUEST_ACTION_BUTTON_WIDTH = 58;
	private static final int REQUEST_ACTION_BUTTON_GAP = 4;
	private static final int FRIEND_REQUESTS_BUTTON_GAP = 4;
	private static final int FRIEND_REQUESTS_BUTTON_WIDTH = 132;

	private final List<GuiEventListener> listeners = new ArrayList<>();
	private final List<Button> rowButtons = new ArrayList<>();
	private final List<RequestRowWidgets> requestRowWidgets = new ArrayList<>();
	private final List<FriendCandidate> renderedOnlineRows = new ArrayList<>();
	private final List<FriendCandidate> renderedRequestRows = new ArrayList<>();
	private final List<FriendCandidate> visibleOnlineRows = new ArrayList<>();
	private final List<FriendCandidate> visibleRequestRows = new ArrayList<>();

	private int x;
	private int y;
	private int width;
	private int height;
	private int friendsPageIndex;
	private int requestPageIndex;
	private ViewMode viewMode = ViewMode.FRIENDS;

	private EditBox searchBox;
	private Button searchButton;
	private Button searchResultButton;
	private Button friendRequestsButton;
	private Button prevPageButton;
	private Button nextPageButton;

	@Override
	public String getTitle() {
		return BlockChatStrings.FIND_FRIENDS_TAB;
	}

	@Override
	public void init(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.friendsPageIndex = 0;
		this.requestPageIndex = 0;
		this.viewMode = ViewMode.FRIENDS;

		Font font = Minecraft.getInstance().font;
		searchBox = new EditBox(
			font,
			contentLeft(),
			searchBoxY(font),
			contentWidth() - SEARCH_BUTTON_WIDTH - SEARCH_ROW_GAP,
			20,
			Component.literal(BlockChatStrings.SEARCH_USERNAME)
		);
		searchBox.setHint(Component.literal(BlockChatStrings.SEARCH_USERNAME));
		searchBox.setMaxLength(16);
		searchBox.setCanLoseFocus(true);

		searchButton = Button.builder(Component.literal(BlockChatStrings.SEARCH_BUTTON), button -> submitSearch())
			.bounds(contentRight() - SEARCH_BUTTON_WIDTH, searchBoxY(font), SEARCH_BUTTON_WIDTH, 20)
			.build();

		friendRequestsButton = Button.builder(Component.empty(), button -> toggleRequestsView())
			.bounds(
				contentLeft() + (contentWidth() - FRIEND_REQUESTS_BUTTON_WIDTH) / 2,
				friendRequestsButtonY(),
				FRIEND_REQUESTS_BUTTON_WIDTH,
				20
			)
			.build();

		prevPageButton = Button.builder(Component.literal(BlockChatStrings.PREVIOUS_PAGE), button -> changePage(-1))
			.bounds(contentLeft(), footerY(), 20, 20)
			.build();
		nextPageButton = Button.builder(Component.literal(BlockChatStrings.NEXT_PAGE), button -> changePage(1))
			.bounds(contentRight() - 20, footerY(), 20, 20)
			.build();

		rebuildInteractiveRows();
	}

	@Override
	public void tick() {
		rebuildInteractiveRows();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		if (viewMode == ViewMode.REQUESTS) {
			renderFriendRequestsView(graphics, font, mouseX, mouseY, partialTick);
		} else {
			renderFindFriendsView(graphics, font, mouseX, mouseY, partialTick);
		}
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return listeners;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		if (viewMode == ViewMode.FRIENDS && searchBox != null && searchBox.mouseClicked(event, fromSelf)) {
			searchBox.setFocused(true);
			return true;
		}

		boolean handled = BlockChatTab.super.mouseClicked(event, fromSelf);
		if (!handled && viewMode == ViewMode.FRIENDS && searchBox != null) {
			searchBox.setFocused(false);
		}
		return handled;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (viewMode == ViewMode.FRIENDS && searchBox != null && searchBox.keyPressed(event)) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				submitSearch();
			}
			return true;
		}
		if (viewMode == ViewMode.FRIENDS && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			submitSearch();
			return true;
		}
		return BlockChatTab.super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (viewMode == ViewMode.FRIENDS && searchBox != null && searchBox.charTyped(event)) {
			return true;
		}
		return BlockChatTab.super.charTyped(event);
	}

	@Override
	public boolean blocksOpenKeyClose() {
		return viewMode == ViewMode.FRIENDS && searchBox != null && searchBox.isFocused();
	}

	private void renderFindFriendsView(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
		graphics.drawString(font, BlockChatStrings.SEARCH_BY_USERNAME, contentLeft(), sectionTopY(), 0xFFFFFFFF, false);
		searchBox.render(graphics, mouseX, mouseY, partialTick);
		searchButton.render(graphics, mouseX, mouseY, partialTick);

		int searchStatusY = searchStatusY(font);
		int searchStatusHeight = renderSearchStatus(graphics, font, searchStatusY, mouseX, mouseY, partialTick);

		int listHeaderY = searchStatusY + searchStatusHeight + SECTION_GAP;
		graphics.drawString(font, BlockChatStrings.ONLINE_ON_THIS_SERVER, contentLeft(), listHeaderY, 0xFFFFFFFF, false);

		int listStartY = listHeaderY + LABEL_TO_CONTROL_GAP + font.lineHeight;
		renderOnlineRows(graphics, font, listStartY, mouseX, mouseY, partialTick);
		renderFriendRequestsButton(graphics, mouseX, mouseY, partialTick);
		renderPagination(graphics, font, mouseX, mouseY, partialTick);
	}

	private void renderFriendRequestsView(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
		int titleY = sectionTopY();
		graphics.drawString(font, BlockChatStrings.friendRequestsViewTitle(BlockChatSocialState.incomingRequestCount()), contentLeft(), titleY, 0xFFFFFFFF, false);
		graphics.drawString(
			font,
			BlockChatStrings.INCOMING_REQUESTS_DESCRIPTION,
			contentLeft(),
			titleY + font.lineHeight + 2,
			0xFFDDDDDD,
			false
		);

		int listStartY = requestListStartY(font);
		renderRequestRows(graphics, font, listStartY, mouseX, mouseY, partialTick);
		renderFriendRequestsButton(graphics, mouseX, mouseY, partialTick);
		renderPagination(graphics, font, mouseX, mouseY, partialTick);
	}

	private int renderSearchStatus(GuiGraphics graphics, Font font, int drawY, int mouseX, int mouseY, float partialTick) {
		SearchResult searchResult = BlockChatSocialState.searchResult();
		String feedback = BlockChatSocialState.searchFeedbackMessage();

		if (BlockChatSocialState.isSearching()) {
			graphics.drawString(font, BlockChatStrings.SEARCHING, contentLeft(), drawY, 0xFFFFFF55, false);
			return font.lineHeight;
		}
		if (feedback != null && !feedback.isBlank()) {
			return renderWrappedStatusText(graphics, font, drawY, feedback, 0xFFFF8888);
		}
		if (searchResult == null) {
			String connectionMessage = BlockChatSocialState.isConnected()
				? BlockChatStrings.SEARCH_REMOTE_HINT
				: BlockChatStrings.SEARCH_RECONNECTING_HINT;
			return renderWrappedStatusText(graphics, font, drawY, connectionMessage, 0xFFDDDDDD);
		}

		renderCandidateRow(
			graphics,
			font,
			drawY - 2,
			new FriendCandidate(searchResult.uuid(), searchResult.username(), searchResult.skinUrl(), null)
		);
		if (searchResultButton != null) {
			searchResultButton.render(graphics, mouseX, mouseY, partialTick);
		}
		return ROW_HEIGHT;
	}

	private int renderWrappedStatusText(GuiGraphics graphics, Font font, int drawY, String text, int color) {
		List<FormattedCharSequence> wrapped = font.split(Component.literal(text), contentWidth());
		if (wrapped.isEmpty()) {
			return 0;
		}

		int currentY = drawY;
		for (FormattedCharSequence line : wrapped) {
			graphics.drawString(font, line, contentLeft(), currentY, color, false);
			currentY += font.lineHeight;
		}
		return wrapped.size() * font.lineHeight;
	}

	private void renderOnlineRows(GuiGraphics graphics, Font font, int startY, int mouseX, int mouseY, float partialTick) {
		if (renderedOnlineRows.isEmpty()) {
			graphics.drawString(font, BlockChatStrings.NO_OTHER_ONLINE_PLAYERS, contentLeft(), startY + 8, 0xFFBBBBBB, false);
			return;
		}

		for (int index = 0; index < renderedOnlineRows.size(); index++) {
			renderCandidateRow(graphics, font, startY + index * ROW_HEIGHT, renderedOnlineRows.get(index));
			rowButtons.get(index).render(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void renderRequestRows(GuiGraphics graphics, Font font, int startY, int mouseX, int mouseY, float partialTick) {
		if (renderedRequestRows.isEmpty()) {
			graphics.drawString(font, BlockChatStrings.NO_INCOMING_FRIEND_REQUESTS, contentLeft(), startY + 8, 0xFFBBBBBB, false);
			return;
		}

		for (int index = 0; index < renderedRequestRows.size(); index++) {
			FriendCandidate candidate = renderedRequestRows.get(index);
			renderRequestRow(graphics, font, startY + index * ROW_HEIGHT, candidate);
			RequestRowWidgets widgets = requestRowWidgets.get(index);
			widgets.acceptButton().render(graphics, mouseX, mouseY, partialTick);
			widgets.ignoreButton().render(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void renderCandidateRow(GuiGraphics graphics, Font font, int rowY, FriendCandidate candidate) {
		int rowBottom = rowY + ROW_HEIGHT;
		graphics.fill(contentLeft(), rowY, contentRight(), rowBottom, 0x44222222);
		graphics.fill(contentLeft(), rowBottom - 1, contentRight(), rowBottom, 0x664A4A4A);

		int faceX = contentLeft() + 4;
		int faceY = rowY + 2;
		renderFace(graphics, candidate, faceX, faceY);

		int buttonX = contentRight() - ACTION_BUTTON_WIDTH;
		int textX = faceX + FACE_SIZE + 6;
		int textWidth = Math.max(1, buttonX - textX - 6);
		graphics.drawString(
			font,
			truncate(font, candidate.username(), textWidth),
			textX,
			rowY + (ROW_HEIGHT - font.lineHeight) / 2,
			0xFFFFFFFF,
			false
		);
	}

	private void renderRequestRow(GuiGraphics graphics, Font font, int rowY, FriendCandidate candidate) {
		int rowBottom = rowY + ROW_HEIGHT;
		graphics.fill(contentLeft(), rowY, contentRight(), rowBottom, 0x44222222);
		graphics.fill(contentLeft(), rowBottom - 1, contentRight(), rowBottom, 0x664A4A4A);

		int faceX = contentLeft() + 4;
		int faceY = rowY + 2;
		renderFace(graphics, candidate, faceX, faceY);

		int ignoreButtonX = contentRight() - REQUEST_ACTION_BUTTON_WIDTH;
		int acceptButtonX = ignoreButtonX - REQUEST_ACTION_BUTTON_GAP - REQUEST_ACTION_BUTTON_WIDTH;
		int textX = faceX + FACE_SIZE + 6;
		int textWidth = Math.max(1, acceptButtonX - textX - 6);
		graphics.drawString(
			font,
			truncate(font, candidate.username(), textWidth),
			textX,
			rowY + (ROW_HEIGHT - font.lineHeight) / 2,
			0xFFFFFFFF,
			false
		);
	}

	private void renderPagination(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
		prevPageButton.render(graphics, mouseX, mouseY, partialTick);
		nextPageButton.render(graphics, mouseX, mouseY, partialTick);

		int totalPages = totalPages();
		String pageText = BlockChatStrings.pageIndicator(currentPageIndex(), totalPages);
		graphics.drawCenteredString(font, pageText, contentLeft() + contentWidth() / 2, y + height - 14, 0xFFE0E0E0);
	}

	private void renderFriendRequestsButton(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (friendRequestsButton == null) {
			return;
		}
		friendRequestsButton.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderFace(GuiGraphics graphics, FriendCandidate candidate, int drawX, int drawY) {
		if (candidate.liveSkin() != null) {
			PlayerFaceRenderer.draw(graphics, candidate.liveSkin(), drawX, drawY, FACE_SIZE);
			return;
		}

		String resolvedSkin = PlayerLookupCache.resolveSkinUrl(candidate.username(), candidate.uuid(), candidate.skinUrl());
		Identifier faceTexture = SkinHelper.getFaceTexture(resolvedSkin, candidate.uuid());
		if (faceTexture != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, faceTexture, drawX, drawY, 0, 0, FACE_SIZE, FACE_SIZE, FACE_SIZE, FACE_SIZE);
			return;
		}

		graphics.fill(drawX, drawY, drawX + FACE_SIZE, drawY + FACE_SIZE, 0xFF4C5B75);
		String fallback = candidate.username().isBlank()
			? "?"
			: candidate.username().substring(0, 1).toUpperCase(Locale.ROOT);
		graphics.drawCenteredString(Minecraft.getInstance().font, fallback, drawX + FACE_SIZE / 2, drawY + 4, 0xFFFFFFFF);
	}

	private void rebuildInteractiveRows() {
		rebuildOnlineRows();
		rebuildRequestRows();
		refreshButtons();
	}

	private void rebuildOnlineRows() {
		visibleOnlineRows.clear();
		visibleOnlineRows.addAll(loadOnlinePlayers());

		int totalPages = totalPages(ViewMode.FRIENDS);
		if (totalPages == 0) {
			friendsPageIndex = 0;
		} else {
			friendsPageIndex = Math.max(0, Math.min(friendsPageIndex, totalPages - 1));
		}

		renderedOnlineRows.clear();
		int startIndex = friendsPageIndex * visibleRowCount(ViewMode.FRIENDS);
		for (int index = startIndex; index < Math.min(startIndex + visibleRowCount(ViewMode.FRIENDS), visibleOnlineRows.size()); index++) {
			renderedOnlineRows.add(visibleOnlineRows.get(index));
		}

		rowButtons.clear();
		searchResultButton = null;
		for (int index = 0; index < renderedOnlineRows.size(); index++) {
			FriendCandidate candidate = renderedOnlineRows.get(index);
			int rowY = onlineListStartY() + index * ROW_HEIGHT;
			rowButtons.add(buildActionButton(candidate, rowY, FriendActionOrigin.ONLINE_LIST));
		}

		SearchResult searchResult = BlockChatSocialState.searchResult();
		if (searchResult != null) {
			searchResultButton = buildActionButton(
				new FriendCandidate(searchResult.uuid(), searchResult.username(), searchResult.skinUrl(), null),
				searchStatusY(Minecraft.getInstance().font) - 2,
				FriendActionOrigin.SEARCH_RESULT
			);
		}
	}

	private void rebuildRequestRows() {
		visibleRequestRows.clear();
		visibleRequestRows.addAll(loadIncomingRequests());

		int totalPages = totalPages(ViewMode.REQUESTS);
		if (totalPages == 0) {
			requestPageIndex = 0;
		} else {
			requestPageIndex = Math.max(0, Math.min(requestPageIndex, totalPages - 1));
		}

		renderedRequestRows.clear();
		requestRowWidgets.clear();
		int startIndex = requestPageIndex * visibleRowCount(ViewMode.REQUESTS);
		for (int index = startIndex; index < Math.min(startIndex + visibleRowCount(ViewMode.REQUESTS), visibleRequestRows.size()); index++) {
			FriendCandidate candidate = visibleRequestRows.get(index);
			renderedRequestRows.add(candidate);
			requestRowWidgets.add(buildRequestRowWidgets(candidate, requestListStartY(Minecraft.getInstance().font) + (index - startIndex) * ROW_HEIGHT));
		}
	}

	private void refreshButtons() {
		if (friendRequestsButton == null || prevPageButton == null || nextPageButton == null || searchButton == null) {
			return;
		}

		int incomingRequestCount = BlockChatSocialState.incomingRequestCount();
		friendRequestsButton.setMessage(
			viewMode == ViewMode.REQUESTS
				? Component.literal(BlockChatStrings.BACK)
				: Component.literal(BlockChatStrings.friendRequestsWithCount(incomingRequestCount))
		);
		friendRequestsButton.active = true;

		if (viewMode == ViewMode.FRIENDS) {
			prevPageButton.active = friendsPageIndex > 0;
			nextPageButton.active = friendsPageIndex + 1 < totalPages(ViewMode.FRIENDS);
			searchButton.active = BlockChatSocialState.isConnected();
		} else {
			prevPageButton.active = requestPageIndex > 0;
			nextPageButton.active = requestPageIndex + 1 < totalPages(ViewMode.REQUESTS);
			searchButton.active = false;
		}

		listeners.clear();
		if (viewMode == ViewMode.FRIENDS) {
			listeners.add(searchBox);
			listeners.add(searchButton);
			if (searchResultButton != null) {
				listeners.add(searchResultButton);
			}
			listeners.add(friendRequestsButton);
			listeners.add(prevPageButton);
			listeners.add(nextPageButton);
			listeners.addAll(rowButtons);
		} else {
			listeners.add(friendRequestsButton);
			listeners.add(prevPageButton);
			listeners.add(nextPageButton);
			for (RequestRowWidgets widgets : requestRowWidgets) {
				listeners.add(widgets.acceptButton());
				listeners.add(widgets.ignoreButton());
			}
		}
	}

	private Button buildActionButton(FriendCandidate candidate, int rowY) {
		return buildActionButton(candidate, rowY, FriendActionOrigin.ONLINE_LIST);
	}

	private Button buildActionButton(FriendCandidate candidate, int rowY, FriendActionOrigin origin) {
		boolean isLocalPlayer = isLocalPlayer(candidate.uuid());
		FriendRelationship relationship = BlockChatSocialState.relationshipFor(candidate.uuid());
		boolean active = !isLocalPlayer && relationship == FriendRelationship.NONE && BlockChatWebSocket.isConnected();
		String label = isLocalPlayer ? BlockChatStrings.YOU : switch (relationship) {
			case NONE -> BlockChatStrings.ADD_FRIEND;
			case PENDING -> BlockChatStrings.PENDING;
			case FRIENDS -> BlockChatStrings.FRIENDS;
		};
		Button button = Button.builder(Component.literal(label), ignored ->
			BlockChatSocialState.requestAddFriend(candidate.uuid(), candidate.username(), candidate.skinUrl(), origin)
		).bounds(contentRight() - ACTION_BUTTON_WIDTH, rowY, ACTION_BUTTON_WIDTH, 20).build();
		button.active = active;
		return button;
	}

	private RequestRowWidgets buildRequestRowWidgets(FriendCandidate candidate, int rowY) {
		Button acceptButton = Button.builder(Component.literal(BlockChatStrings.ACCEPT), ignored ->
			BlockChatSocialState.acceptIncomingFriendRequest(candidate.uuid())
		).bounds(contentRight() - (REQUEST_ACTION_BUTTON_WIDTH * 2) - REQUEST_ACTION_BUTTON_GAP, rowY, REQUEST_ACTION_BUTTON_WIDTH, 20).build();
		acceptButton.active = BlockChatSocialState.isConnected();

		Button ignoreButton = Button.builder(Component.literal(BlockChatStrings.IGNORE), ignored ->
			BlockChatSocialState.ignoreIncomingFriendRequest(candidate.uuid())
		).bounds(contentRight() - REQUEST_ACTION_BUTTON_WIDTH, rowY, REQUEST_ACTION_BUTTON_WIDTH, 20).build();
		ignoreButton.active = BlockChatSocialState.isConnected();

		return new RequestRowWidgets(candidate, acceptButton, ignoreButton);
	}

	private void submitSearch() {
		if (searchBox == null) {
			return;
		}
		BlockChatSocialState.submitSearch(searchBox.getValue());
	}

	private void changePage(int delta) {
		if (viewMode == ViewMode.FRIENDS) {
			friendsPageIndex += delta;
		} else {
			requestPageIndex += delta;
		}
		rebuildInteractiveRows();
	}

	private void toggleRequestsView() {
		viewMode = viewMode == ViewMode.FRIENDS ? ViewMode.REQUESTS : ViewMode.FRIENDS;
		if (viewMode == ViewMode.REQUESTS && searchBox != null) {
			searchBox.setFocused(false);
		}
		rebuildInteractiveRows();
	}

	private List<FriendCandidate> loadOnlinePlayers() {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return List.of();
		}

		UUID localPlayerId = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		List<FriendCandidate> onlinePlayers = new ArrayList<>();
		for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
			UUID playerId = playerInfo.getProfile().id();
			if (playerId == null || playerId.equals(localPlayerId)) {
				continue;
			}
			onlinePlayers.add(new FriendCandidate(
				BlockChatPlayerUuid.canonicalize(playerId.toString()),
				playerInfo.getProfile().name(),
				null,
				playerInfo.getSkin()
			));
		}
		onlinePlayers.sort(Comparator.comparing(FriendCandidate::username, String.CASE_INSENSITIVE_ORDER));
		return onlinePlayers;
	}

	private boolean isLocalPlayer(String uuid) {
		uuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (uuid == null) {
			return false;
		}
		UUID localPlayerId = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		return localPlayerId != null && uuid.equals(BlockChatPlayerUuid.canonicalize(localPlayerId.toString()));
	}

	private int visibleRowCount() {
		return visibleRowCount(viewMode);
	}

	private int visibleRowCount(ViewMode viewMode) {
		int availableHeight = friendRequestsButtonY() - listStartY(viewMode);
		return Math.max(1, availableHeight / ROW_HEIGHT);
	}

	private int totalPages() {
		return totalPages(viewMode);
	}

	private int totalPages(ViewMode viewMode) {
		List<FriendCandidate> source = viewMode == ViewMode.REQUESTS ? visibleRequestRows : visibleOnlineRows;
		int rowCount = visibleRowCount(viewMode);
		if (source.isEmpty()) {
			return 0;
		}
		return (source.size() + rowCount - 1) / rowCount;
	}

	private int onlineListStartY() {
		Font font = Minecraft.getInstance().font;
		return searchStatusY(font) + searchStatusHeight(font) + SECTION_GAP + LABEL_TO_CONTROL_GAP + font.lineHeight;
	}

	private int requestListStartY(Font font) {
		return sectionTopY() + font.lineHeight + 2 + SECTION_GAP;
	}

	private int listStartY(ViewMode viewMode) {
		if (viewMode == ViewMode.REQUESTS) {
			return requestListStartY(Minecraft.getInstance().font);
		}
		return onlineListStartY();
	}

	private int searchStatusY(Font font) {
		return searchBoxY(font) + 20 + CONTROL_TO_STATUS_GAP;
	}

	private int searchStatusHeight(Font font) {
		SearchResult searchResult = BlockChatSocialState.searchResult();
		String feedback = BlockChatSocialState.searchFeedbackMessage();
		if (BlockChatSocialState.isSearching()) {
			return font.lineHeight;
		}
		if (feedback != null && !feedback.isBlank()) {
			return wrappedLineCount(font, feedback) * font.lineHeight;
		}
		if (searchResult == null) {
			String connectionMessage = BlockChatSocialState.isConnected()
				? BlockChatStrings.SEARCH_REMOTE_HINT
				: BlockChatStrings.SEARCH_RECONNECTING_HINT;
			return wrappedLineCount(font, connectionMessage) * font.lineHeight;
		}
		return ROW_HEIGHT;
	}

	private int wrappedLineCount(Font font, String text) {
		return Math.max(1, font.split(Component.literal(text), contentWidth()).size());
	}

	private int sectionTopY() {
		return y + OUTER_PADDING_TOP;
	}

	private int searchBoxY(Font font) {
		return sectionTopY() + font.lineHeight + LABEL_TO_CONTROL_GAP;
	}

	private int contentLeft() {
		return x + OUTER_PADDING_X;
	}

	private int contentRight() {
		return x + width - OUTER_PADDING_X;
	}

	private int contentWidth() {
		return width - OUTER_PADDING_X * 2;
	}

	private int footerY() {
		return y + height - FOOTER_HEIGHT;
	}

	private int friendRequestsButtonY() {
		return footerY() - 24;
	}

	private int currentPageIndex() {
		return viewMode == ViewMode.FRIENDS ? friendsPageIndex : requestPageIndex;
	}

	private String truncate(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	private List<FriendCandidate> loadIncomingRequests() {
		List<FriendCandidate> incomingRequests = new ArrayList<>();
		for (BlockChatSocialState.FriendRecord record : BlockChatSocialState.incomingFriendRequests()) {
			incomingRequests.add(new FriendCandidate(record.uuid(), record.username(), record.skinUrl(), null));
		}
		incomingRequests.sort(Comparator.comparing(FriendCandidate::username, String.CASE_INSENSITIVE_ORDER));
		return incomingRequests;
	}

	private record FriendCandidate(String uuid, String username, String skinUrl, PlayerSkin liveSkin) {
	}

	private record RequestRowWidgets(FriendCandidate candidate, Button acceptButton, Button ignoreButton) {
	}

	private enum ViewMode {
		FRIENDS,
		REQUESTS
	}
}
