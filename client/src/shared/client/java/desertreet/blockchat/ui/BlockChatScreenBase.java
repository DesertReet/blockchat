package desertreet.blockchat.ui;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.glfw.GLFW;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.chat.BlockChatChatState;
import desertreet.blockchat.chat.BlockChatInboxState;
import desertreet.blockchat.compat.BlockChatGuiCompat;
import desertreet.blockchat.compat.BlockChatWidgetRenderers;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.send.BlockChatSnapSender;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.social.BlockChatSocialState;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

abstract class BlockChatScreenBase extends Screen {

	// Creative-style tab sprites
	private static final Identifier[] SELECTED_TOP_TABS = new Identifier[]{
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_selected_1"),
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_selected_2"),
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_selected_7")
	};
	private static final Identifier[] UNSELECTED_TOP_TABS = new Identifier[]{
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_unselected_1"),
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_unselected_2"),
		Identifier.withDefaultNamespace("container/creative_inventory/tab_top_unselected_7")
	};

	private static final int TAB_WIDTH = 26;
	private static final int TAB_HEIGHT = 32;
	private static final int PANEL_W = 300;
	private static final int PANEL_H = 200;
	private static final int DELETE_MODAL_W = 276;
	private static final int DELETE_MODAL_H = 176;
	private static final int TITLE_TEXT_COLOR = 0xFF404040;
	private static final int TITLE_LINK_HOVER_COLOR = 0xFF2A6EEA;

	// Tab icon items
	private static final ItemStack[] TAB_ICONS = {
		new ItemStack(Items.WRITABLE_BOOK),
		new ItemStack(Items.SPYGLASS),
		new ItemStack(Items.COMPARATOR)
	};
	private static final int INERT_MOUSE_X = -1_000_000;
	private static final int INERT_MOUSE_Y = -1_000_000;

	private static int lastTabIndex = 0;

	private PendingCapture.PendingMedia pendingMedia;
	private final List<BlockChatTab> tabs = new ArrayList<>();
	private int activeTabIndex;
	private DraftComposerWidget composer;
	private boolean showingComposer;
	private SendRecipientsWidget recipientPicker;
	private boolean showingRecipientPicker;
	private InboxSnapViewerWidget inboxViewer;
	private CompletableFuture<BlockChatInboxState.ViewedSnap> pendingInboxOpen;
	private boolean loadingInboxViewer;
	private Button cancelDeleteAccountButton;
	private Button confirmDeleteAccountButton;
	private boolean showingDeleteAccountModal;
	private boolean deletingAccount;
	private String deleteAccountErrorMessage;
	private CompletableFuture<Void> pendingDeleteAccount;

	// Panel bounds
	private int panelX, panelY;
	private int contentX, contentY, contentW, contentH;

	// Login UI state
	private enum LoginState { IDLE, REQUESTING_CODE, CODE_READY, VERIFYING, ERROR }
	private LoginState loginState = LoginState.IDLE;
	private Button loginButton;
	private Button openLinkButton;
	private Button verifyButton;
	private Button retryButton;
	private Button copyCodeButton;
	private String deviceCode;
	private String userCode;
	private String verificationUri;
	private String loginErrorMessage;
	private boolean linkOpened;
	private long linkOpenedAt;
	private long codeCopiedAt;
	private long handCursor;
	private boolean cursorIsHand;

	protected BlockChatScreenBase(PendingCapture.PendingMedia pendingMedia) {
		super(Component.literal(BlockChatStrings.screenTitle()));
		this.pendingMedia = pendingMedia;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		tabs.clear();

		tabs.add(new ChatTab(this::openOldestUnreadForSender));
		tabs.add(new FindFriendsTab());
		tabs.add(new SettingsTab(this::openDeleteAccountModal));

		activeTabIndex = lastTabIndex;
		if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
			activeTabIndex = 0;
		}

		panelX = (this.width - PANEL_W) / 2;
		panelY = (this.height - PANEL_H) / 2;

		// Content area inside the panel (below title)
		int titleBarH = 14;
		contentX = panelX + 6;
		contentY = panelY + titleBarH;
		contentW = PANEL_W - 12;
		contentH = PANEL_H - titleBarH - 6;

		for (BlockChatTab tab : tabs) {
			tab.init(contentX, contentY, contentW, contentH);
		}

		if (BlockChatAuth.isLoggedIn()) {
			BlockChatSocialState.installWebSocketListener();
			BlockChatChatState.installWebSocketListener();
			BlockChatInboxState.installWebSocketListener();
			BlockChatSocialState.requestFriendListIfConnected();
			BlockChatChatState.requestRecentsIfConnected();
			BlockChatInboxState.requestSnapsIfConnected();
		} else {
			BlockChatSocialState.clear();
			BlockChatChatState.clear();
			BlockChatInboxState.clear();
		}

		initLoginButtons();
		initDeleteAccountModalButtons();
		maybeShowPendingComposer();
	}

	private void initLoginButtons() {
		int btnW = 200;
		int centerX = panelX + (PANEL_W - btnW) / 2;
		int loginCenterY = contentY + contentH / 2;

		loginButton = Button.builder(
			Component.literal(BlockChatStrings.LOGIN_BUTTON),
			b -> onLoginClicked()
		).bounds(centerX, loginCenterY - 10, btnW, 20).build();

		openLinkButton = Button.builder(
			Component.literal(BlockChatStrings.OPEN_LOGIN_PAGE_BUTTON),
			b -> onOpenLinkClicked()
		).bounds(centerX, loginCenterY - 10, btnW, 20).build();

		copyCodeButton = Button.builder(
			Component.literal(BlockChatStrings.COPY_CODE_BUTTON),
			b -> onCopyCodeClicked()
		).bounds(centerX, loginCenterY + 16, btnW, 20).build();

		int bottomBtnY = panelY + PANEL_H - 5 - 4 - 20; // inside panel bottom
		verifyButton = Button.builder(
			Component.literal(BlockChatStrings.VERIFY_LINK_BUTTON),
			b -> onVerifyClicked()
		).bounds(centerX, bottomBtnY, btnW, 20).build();
		verifyButton.active = false;

		retryButton = Button.builder(
			Component.literal(BlockChatStrings.TRY_AGAIN_BUTTON),
			b -> onRetryClicked()
		).bounds(centerX, loginCenterY + 16, btnW, 20).build();
	}

	private void initDeleteAccountModalButtons() {
		int boxX = (this.width - DELETE_MODAL_W) / 2;
		int boxY = (this.height - DELETE_MODAL_H) / 2;
		int buttonWidth = 110;
		int buttonY = boxY + DELETE_MODAL_H - 30;
		int buttonGap = 8;
		int totalWidth = buttonWidth * 2 + buttonGap;
		int startX = boxX + (DELETE_MODAL_W - totalWidth) / 2;

		cancelDeleteAccountButton = Button.builder(
			Component.literal(BlockChatStrings.CANCEL_BUTTON),
			b -> closeDeleteAccountModal()
		).bounds(startX, buttonY, buttonWidth, 20).build();

		confirmDeleteAccountButton = Button.builder(
			Component.literal(BlockChatStrings.DELETE_ACCOUNT_BUTTON).withStyle(ChatFormatting.RED),
			b -> beginDeleteAccount()
		).bounds(startX + buttonWidth + buttonGap, buttonY, buttonWidth, 20).build();

		refreshDeleteAccountModalButtons();
	}

	protected abstract void renderMenuBackgroundCompat(GuiGraphics graphics);

	protected final void renderBlockChatScreen(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderMenuBackgroundCompat(graphics);
		boolean overlayLayerOpen = hasOverlayLayerOpen();

		// Unselected tabs render BEHIND the panel
		if (!overlayLayerOpen) {
			renderTabs(graphics, false);
		}

		// Panel background (Minecraft inventory style)
		renderContainerBackground(graphics);

		// Selected tab renders IN FRONT of the panel
		if (!overlayLayerOpen) {
			renderTabs(graphics, true);
		}

		boolean titleLinkHovered = renderTitle(graphics, mouseX, mouseY);

		if (!BlockChatAuth.isLoggedIn()) {
			renderLoginUI(graphics, mouseX, mouseY, partialTick);
		} else if (showingDeleteAccountModal) {
			BlockChatTab tab = tabs.get(activeTabIndex);
			tab.render(graphics, INERT_MOUSE_X, INERT_MOUSE_Y, partialTick);
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
			renderDeleteAccountModal(graphics, mouseX, mouseY, partialTick);
		} else if (showingRecipientPicker && recipientPicker != null) {
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
			recipientPicker.render(graphics, mouseX, mouseY, partialTick);
		} else if (showingComposer && composer != null) {
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
			composer.render(graphics, mouseX, mouseY, partialTick);
		} else if (loadingInboxViewer) {
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
			renderInboxLoading(graphics);
		} else if (inboxViewer != null) {
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
			inboxViewer.render(graphics, mouseX, mouseY, partialTick);
		} else {
			BlockChatTab tab = tabs.get(activeTabIndex);
			tab.render(graphics, mouseX, mouseY, partialTick);
		}

		// Tab tooltips (render last so they're on top)
		if (isHeaderInteractive()) {
			boolean tabHovered = renderTabTooltip(graphics, mouseX, mouseY);
			setCursorHand(titleLinkHovered || tabHovered);
		} else {
			setCursorHand(false);
		}
	}

	private boolean renderTitle(GuiGraphics graphics, int mouseX, int mouseY) {
		int titleX = panelX + 8;
		int titleY = panelY + 4;
		graphics.drawString(this.font, BlockChatStrings.AUTHOR_PREFIX, titleX, titleY, TITLE_TEXT_COLOR, false);

		int linkX = titleX + this.font.width(BlockChatStrings.AUTHOR_PREFIX);
		boolean hovered = isHoveringTitleLink(mouseX, mouseY);
		int linkColor = hovered ? TITLE_LINK_HOVER_COLOR : TITLE_TEXT_COLOR;
		graphics.drawString(this.font, BlockChatStrings.AUTHOR_NAME, linkX, titleY, linkColor, false);
		if (hovered) {
			int underlineY = titleY + this.font.lineHeight - 1;
			graphics.fill(linkX, underlineY, linkX + this.font.width(BlockChatStrings.AUTHOR_NAME), underlineY + 1, linkColor);
		}
		return hovered;
	}

	private void renderContainerBackground(GuiGraphics graphics) {
		// Outer border (dark)
		graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFFC6C6C6);

		// Top highlight
		graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, 0xFFFFFFFF);
		// Left highlight
		graphics.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, 0xFFFFFFFF);
		// Bottom shadow (darker)
		graphics.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);
		// Right shadow
		graphics.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);

		// Inner border - top-left dark
		graphics.fill(panelX + 4, panelY + 13, panelX + PANEL_W - 4, panelY + 14, 0xFF373737);
		graphics.fill(panelX + 4, panelY + 13, panelX + 5, panelY + PANEL_H - 4, 0xFF373737);

		// Inner border - bottom-right light
		graphics.fill(panelX + 4, panelY + PANEL_H - 5, panelX + PANEL_W - 4, panelY + PANEL_H - 4, 0xFFFFFFFF);
		graphics.fill(panelX + PANEL_W - 5, panelY + 13, panelX + PANEL_W - 4, panelY + PANEL_H - 4, 0xFFFFFFFF);

		// Inner content area (dark gray)
		graphics.fill(panelX + 5, panelY + 14, panelX + PANEL_W - 5, panelY + PANEL_H - 5, 0xFF8B8B8B);
	}

	private void renderTabs(GuiGraphics graphics, boolean selectedOnly) {
		for (int i = 0; i < tabs.size(); i++) {
			boolean selected = (i == activeTabIndex);
			if (selected != selectedOnly) continue;

			int tabX = panelX + i * TAB_WIDTH;
			// Selected tab extends further down to overlap the panel top border
			int tabY = panelY - TAB_HEIGHT + (selected ? 4 : 1);

			Identifier sprite = selected ? SELECTED_TOP_TABS[i] : UNSELECTED_TOP_TABS[i];
			BlockChatGuiCompat.blitSprite(graphics, sprite, tabX, tabY, TAB_WIDTH, TAB_HEIGHT);

			// Render item icon centered on the tab
			int iconX = tabX + (TAB_WIDTH - 16) / 2;
			int iconY = tabY + (selected ? 8 : 10);
			graphics.renderItem(TAB_ICONS[i], iconX, iconY);
		}
	}

	private boolean renderTabTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		boolean hovering = false;
		for (int i = 0; i < tabs.size(); i++) {
			boolean selected = (i == activeTabIndex);
			int tabX = panelX + i * TAB_WIDTH;
			int tabY = panelY - TAB_HEIGHT + (selected ? 4 : 1);
			if (mouseX >= tabX && mouseX < tabX + TAB_WIDTH && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
				BlockChatGuiCompat.setTooltip(graphics, this.font, Component.literal(tabs.get(i).getTitle()), mouseX, mouseY);
				hovering = true;
				break;
			}
		}
		return hovering;
	}

	private boolean hasOverlayLayerOpen() {
		return showingDeleteAccountModal
			|| (showingRecipientPicker && recipientPicker != null)
			|| (showingComposer && composer != null)
			|| loadingInboxViewer
			|| inboxViewer != null;
	}

	private boolean isHeaderInteractive() {
		return !showingDeleteAccountModal
			&& !showingComposer
			&& !showingRecipientPicker
			&& !loadingInboxViewer
			&& inboxViewer == null;
	}

	private boolean isHoveringTitleLink(double mouseX, double mouseY) {
		if (!isHeaderInteractive()) {
			return false;
		}
		int linkX = panelX + 8 + this.font.width(BlockChatStrings.AUTHOR_PREFIX);
		int linkY = panelY + 4;
		int linkWidth = this.font.width(BlockChatStrings.AUTHOR_NAME);
		return mouseX >= linkX
			&& mouseX < linkX + linkWidth
			&& mouseY >= linkY
			&& mouseY < linkY + this.font.lineHeight;
	}

	private void onTitleLinkClicked() {
		ConfirmLinkScreen.confirmLinkNow(this, BlockChatStrings.AUTHOR_URL);
	}

	private void setCursorHand(boolean hand) {
		if (hand == cursorIsHand) return;
		long windowHandle = Minecraft.getInstance().getWindow().handle();
		if (hand) {
			if (handCursor == 0) {
				handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_POINTING_HAND_CURSOR);
			}
			GLFW.glfwSetCursor(windowHandle, handCursor);
		} else {
			GLFW.glfwSetCursor(windowHandle, 0);
		}
		cursorIsHand = hand;
	}

	private void renderLoginUI(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int centerX = panelX + PANEL_W / 2;
		int loginCenterY = contentY + contentH / 2;

		switch (loginState) {
			case IDLE -> {
				graphics.drawCenteredString(font, BlockChatStrings.LOGIN_PROMPT, centerX, loginCenterY - 30, 0xFFFFFFFF);
				BlockChatWidgetRenderers.render(loginButton, graphics, mouseX, mouseY, partialTick);
			}
			case REQUESTING_CODE -> {
				graphics.drawCenteredString(font, BlockChatStrings.REQUESTING_LOGIN_CODE, centerX, loginCenterY - 4, 0xFFFFFF55);
			}
			case CODE_READY -> {
				graphics.drawCenteredString(font, BlockChatStrings.YOUR_LOGIN_CODE, centerX, loginCenterY - 46, 0xFFFFFFFF);
				// Larger code display
				graphics.drawCenteredString(font, userCode, centerX, loginCenterY - 30, 0xFF55FF55);

				BlockChatWidgetRenderers.render(openLinkButton, graphics, mouseX, mouseY, partialTick);

				// Enable verify button 5 seconds after link opened
				if (linkOpened && System.currentTimeMillis() - linkOpenedAt >= 5000) {
					verifyButton.active = true;
				}
				BlockChatWidgetRenderers.render(verifyButton, graphics, mouseX, mouseY, partialTick);
				if (codeCopiedAt > 0 && System.currentTimeMillis() - codeCopiedAt >= 5000) {
					copyCodeButton.setMessage(Component.literal(BlockChatStrings.COPY_CODE_BUTTON));
					codeCopiedAt = 0;
				}
				BlockChatWidgetRenderers.render(copyCodeButton, graphics, mouseX, mouseY, partialTick);
			}
			case VERIFYING -> {
				graphics.drawCenteredString(font, BlockChatStrings.VERIFYING, centerX, loginCenterY - 4, 0xFFFFFF55);
			}
			case ERROR -> {
				String errMsg = loginErrorMessage != null ? loginErrorMessage : BlockChatStrings.AN_ERROR_OCCURRED;
				List<String> lines = wrapText(font, errMsg, contentW - 20);
				int startY = loginCenterY - 30 - (lines.size() * 10) / 2;
				for (int i = 0; i < lines.size(); i++) {
					graphics.drawCenteredString(font, lines.get(i), centerX, startY + i * 10, 0xFFFF5555);
				}
				BlockChatWidgetRenderers.render(retryButton, graphics, mouseX, mouseY, partialTick);
			}
		}
	}

	private List<String> wrapText(Font font, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			if (current.isEmpty()) {
				current.append(word);
			} else if (font.width(current + " " + word) <= maxWidth) {
				current.append(" ").append(word);
			} else {
				lines.add(current.toString());
				current = new StringBuilder(word);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		if (showingDeleteAccountModal) {
			return handleDeleteAccountMouseClick(event, fromSelf);
		}
		if (event.button() == 0 && isHoveringTitleLink(event.x(), event.y())) {
			onTitleLinkClicked();
			return true;
		}
		// Check tab clicks
		if (!showingComposer && !showingRecipientPicker && handleTabClick(event.x(), event.y())) {
			return true;
		}

		if (!BlockChatAuth.isLoggedIn()) {
			return handleLoginMouseClick(event, fromSelf);
		}
		if (inboxViewer != null) {
			if (inboxViewer.mouseClicked(event, fromSelf)) {
				return true;
			}
			return true;
		}
		if (loadingInboxViewer) {
			return true;
		}
		if (showingRecipientPicker && recipientPicker != null) {
			if (recipientPicker.mouseClicked(event, fromSelf)) {
				return true;
			}
		} else
		if (showingComposer && composer != null) {
			if (composer.mouseClicked(event, fromSelf)) {
				return true;
			}
		} else if (dispatchMouseClickedToActiveTab(event, fromSelf)) {
			return true;
		}
		return super.mouseClicked(event, fromSelf);
	}

	private boolean handleTabClick(double mouseX, double mouseY) {
		for (int i = 0; i < tabs.size(); i++) {
			int tabX = panelX + i * TAB_WIDTH;
			boolean selected = (i == activeTabIndex);
			int tabY = panelY - TAB_HEIGHT + (selected ? 4 : 1);
			if (mouseX >= tabX && mouseX < tabX + TAB_WIDTH && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
				selectTab(i);
				return true;
			}
		}
		return false;
	}

	private boolean handleLoginMouseClick(MouseButtonEvent event, boolean fromSelf) {
		return switch (loginState) {
			case IDLE -> loginButton.mouseClicked(event, fromSelf);
			case CODE_READY -> openLinkButton.mouseClicked(event, fromSelf)
				|| verifyButton.mouseClicked(event, fromSelf)
				|| copyCodeButton.mouseClicked(event, fromSelf);
			case ERROR -> retryButton.mouseClicked(event, fromSelf);
			default -> false;
		};
	}

	private boolean handleDeleteAccountMouseClick(MouseButtonEvent event, boolean fromSelf) {
		refreshDeleteAccountModalButtons();
		if (cancelDeleteAccountButton.mouseClicked(event, fromSelf)) {
			return true;
		}
		if (confirmDeleteAccountButton.mouseClicked(event, fromSelf)) {
			return true;
		}
		return true;
	}

	private void onLoginClicked() {
		loginState = LoginState.REQUESTING_CODE;
		loginErrorMessage = null;
		linkOpened = false;
		BlockChatAuth.requestDeviceCode().whenComplete((result, ex) -> {
			Minecraft.getInstance().execute(() -> {
				if (ex != null) {
					loginState = LoginState.ERROR;
					loginErrorMessage = getRootMessage(ex);
					return;
				}
				deviceCode = result.device_code;
				userCode = result.user_code;
				verificationUri = result.verification_uri;
				loginState = LoginState.CODE_READY;
				verifyButton.active = false;
				linkOpened = false;
			});
		});
	}

	private void onOpenLinkClicked() {
		if (verificationUri != null) {
			ConfirmLinkScreen.confirmLinkNow(this, verificationUri);
			linkOpened = true;
			linkOpenedAt = System.currentTimeMillis();
		}
	}

	private void onCopyCodeClicked() {
		if (userCode != null) {
			Minecraft.getInstance().keyboardHandler.setClipboard(userCode);
			codeCopiedAt = System.currentTimeMillis();
			copyCodeButton.setMessage(Component.literal(BlockChatStrings.COPIED));
		}
	}

	private void onVerifyClicked() {
		if (deviceCode == null) return;
		loginState = LoginState.VERIFYING;
		BlockChatAuth.verify(deviceCode).whenComplete((result, ex) -> {
			Minecraft.getInstance().execute(() -> {
				if (ex != null) {
					loginState = LoginState.ERROR;
					loginErrorMessage = getRootMessage(ex);
					return;
				}
				loginState = LoginState.IDLE;
				BlockChatWebSocket.connect();
				this.rebuildWidgets();
			});
		});
	}

	private void onRetryClicked() {
		loginState = LoginState.IDLE;
		loginErrorMessage = null;
		deviceCode = null;
		userCode = null;
		verificationUri = null;
		linkOpened = false;
	}

	private String getRootMessage(Throwable ex) {
		return getRootMessage(ex, "BlockChat auth error");
	}

	private String getRootMessage(Throwable ex, String logMessage) {
		Throwable cause = ex;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		BlockChatClientMod.LOGGER.error(logMessage, cause);
		String sanitized = sanitizeAuthErrorMessage(cause.getMessage());
		if (sanitized != null) {
			return sanitized;
		}
		return BlockChatStrings.connectionFailed(cause.getClass().getSimpleName());
	}

	private String sanitizeAuthErrorMessage(String message) {
		if (message == null) {
			return null;
		}
		String compact = message.replaceAll("\\s+", " ").trim();
		if (compact.isEmpty()) {
			return null;
		}
		String lower = compact.toLowerCase();
		if (lower.contains("com.google.gson")
			|| lower.contains("jsonprimitive")
			|| lower.contains("jsonobject")
			|| (lower.contains("expected") && lower.contains(" at path $"))) {
			return BlockChatStrings.SIGN_IN_UNEXPECTED_SERVER_RESPONSE;
		}
		if (compact.length() > 240) {
			return compact.substring(0, 237) + "...";
		}
		return compact;
	}

	@Override
	public void tick() {
		super.tick();
		if (BlockChatAuth.isLoggedIn()) {
			PlayerLookupCache.tick();
			refreshDeleteAccountModalButtons();
			maybeShowPendingComposer();
			if (showingRecipientPicker && recipientPicker != null) {
				recipientPicker.tick();
			} else if (!showingComposer && !showingRecipientPicker && !loadingInboxViewer && inboxViewer == null && !showingDeleteAccountModal) {
				BlockChatTab tab = tabs.get(activeTabIndex);
				tab.tick();
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (showingDeleteAccountModal) {
			return true;
		}
		if (inboxViewer != null || loadingInboxViewer) {
			return true;
		}
		if (BlockChatAuth.isLoggedIn() && showingRecipientPicker && recipientPicker != null) {
			if (recipientPicker.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
				return true;
			}
		}
		if (BlockChatAuth.isLoggedIn() && !showingComposer && !showingRecipientPicker) {
			if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
				if (tabs.get(activeTabIndex).mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
					return true;
				}
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (showingDeleteAccountModal) {
			return true;
		}
		if (inboxViewer != null || loadingInboxViewer) {
			return true;
		}
		if (showingRecipientPicker) {
			return false;
		}
		if (showingComposer && composer != null && composer.mouseDragged(event, dragX, dragY)) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (showingDeleteAccountModal) {
			return true;
		}
		if (inboxViewer != null || loadingInboxViewer) {
			return true;
		}
		if (showingRecipientPicker) {
			return super.mouseReleased(event);
		}
		if (showingComposer && composer != null) {
			composer.mouseReleased(event);
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (showingDeleteAccountModal) {
			if (!deletingAccount && (event.key() == GLFW.GLFW_KEY_ESCAPE || BlockChatClientMod.getOpenKey().matches(event))) {
				closeDeleteAccountModal();
			}
			return true;
		}
		if (showingRecipientPicker && recipientPicker != null) {
			if (recipientPicker.keyPressed(event)) {
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				this.onClose();
				return true;
			}
			if (BlockChatClientMod.getOpenKey().matches(event)) {
				if (recipientPicker.blocksOpenKeyClose()) {
					return true;
				}
				this.onClose();
				return true;
			}
		}
		if (showingComposer && composer != null) {
			if (composer.keyPressed(event)) {
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				this.onClose();
				return true;
			}
			if (BlockChatClientMod.getOpenKey().matches(event)) {
				if (composer.blocksOpenKeyClose()) {
					return true;
				}
				this.onClose();
				return true;
			}
		}
		if (inboxViewer != null) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || BlockChatClientMod.getOpenKey().matches(event)) {
				dismissInboxViewer();
				return true;
			}
			return true;
		}
		if (loadingInboxViewer) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || BlockChatClientMod.getOpenKey().matches(event)) {
				cancelInboxLoading();
				return true;
			}
			return true;
		}
		// Allow the open key (U) to close the screen unless the active tab is in text-entry mode.
		if (BlockChatClientMod.getOpenKey().matches(event) && !activeTabBlocksOpenKeyClose()) {
			this.onClose();
			return true;
		}
		if (!showingComposer && dispatchKeyPressedToActiveTab(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (showingDeleteAccountModal) {
			return true;
		}
		if (inboxViewer != null || loadingInboxViewer) {
			return true;
		}
		if (showingRecipientPicker && recipientPicker != null) {
			if (recipientPicker.charTyped(event)) {
				return true;
			}
		} else
		if (showingComposer && composer != null) {
			if (composer.charTyped(event)) {
				return true;
			}
		} else if (dispatchCharTypedToActiveTab(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
		if ((showingComposer && composer != null)
			|| (showingRecipientPicker && recipientPicker != null)
			|| inboxViewer != null
			|| showingDeleteAccountModal
			|| loadingInboxViewer) {
			return Optional.empty();
		}
		return super.getChildAt(mouseX, mouseY);
	}

	@Override
	public void onClose() {
		setCursorHand(false);
		if (handCursor != 0) {
			GLFW.glfwDestroyCursor(handCursor);
			handCursor = 0;
		}
		lastTabIndex = activeTabIndex;
		BlockChatConfig.setLastTabIndex(activeTabIndex);
		BlockChatConfig.save();
		closeDeleteAccountModal();
		dismissInboxViewer();
		if (showingComposer || composer != null) {
			dismissComposer();
		}
		for (BlockChatTab tab : tabs) {
			tab.onClose();
		}
		super.onClose();
	}

	/**
	 * Called by tabs that need to trigger a full screen rebuild (e.g. after sign out).
	 */
	public void rebuildFromTab() {
		this.rebuildWidgets();
	}

	private void selectTab(int index) {
		if (showingComposer || showingRecipientPicker || showingDeleteAccountModal) {
			return;
		}
		activeTabIndex = index;
		lastTabIndex = index;
	}

	private void openDeleteAccountModal() {
		if (!BlockChatAuth.isLoggedIn()) {
			return;
		}
		showingDeleteAccountModal = true;
		deleteAccountErrorMessage = null;
		refreshDeleteAccountModalButtons();
	}

	private void closeDeleteAccountModal() {
		if (deletingAccount) {
			return;
		}
		showingDeleteAccountModal = false;
		deleteAccountErrorMessage = null;
	}

	private void beginDeleteAccount() {
		if (deletingAccount || !BlockChatWebSocket.isConnected()) {
			return;
		}
		CompletableFuture<Void> future = BlockChatWebSocket.deleteAccount();
		deletingAccount = true;
		deleteAccountErrorMessage = null;
		pendingDeleteAccount = future;
		refreshDeleteAccountModalButtons();
		future.whenComplete((ignored, error) ->
			Minecraft.getInstance().execute(() -> completeDeleteAccount(future, error))
		);
	}

	private void completeDeleteAccount(CompletableFuture<Void> future, Throwable error) {
		if (future != pendingDeleteAccount) {
			return;
		}
		pendingDeleteAccount = null;
		deletingAccount = false;
		refreshDeleteAccountModalButtons();
		if (error != null) {
			deleteAccountErrorMessage = getRootMessage(error, "BlockChat account deletion failed");
			return;
		}
		showingDeleteAccountModal = false;
		deleteAccountErrorMessage = null;
		BlockChatWebSocket.disconnect();
		BlockChatAuth.logout();
		this.onClose();
	}

	private void dismissComposer() {
		if (recipientPicker != null) {
			recipientPicker.onClose();
			recipientPicker = null;
		}
		showingRecipientPicker = false;
		if (composer != null) {
			composer.onClose();
		}
		showingComposer = false;
		composer = null;
		pendingMedia = null;
		PendingCapture.clear();
	}

	private void maybeShowPendingComposer() {
		if (showingComposer || composer != null || inboxViewer != null || loadingInboxViewer || showingRecipientPicker || showingDeleteAccountModal) {
			return;
		}
		if (pendingMedia == null) {
			pendingMedia = PendingCapture.consumeIfReady();
		}
		if (pendingMedia == null) {
			return;
		}

		showingComposer = true;
		int margin = 20;
		int maxOuterW = this.width - margin * 2;
		int composerHeight = Math.max(280, this.height - margin * 2);
		int mediaW = pendingMedia.videoWidth();
		int mediaH = pendingMedia.videoHeight();
		if (pendingMedia.type() == PendingCapture.MediaType.IMAGE) {
			try (InputStream is = Files.newInputStream(pendingMedia.path());
				 NativeImage probe = NativeImage.read(is)) {
				if (probe != null) {
					mediaW = probe.getWidth();
					mediaH = probe.getHeight();
				}
			} catch (Exception ignored) {
				mediaW = 0;
				mediaH = 0;
			}
		}
		int composerWidth = DraftComposerWidget.suggestComposerWidth(
			composerHeight, mediaW, mediaH, maxOuterW
		);
		int composerX = (this.width - composerWidth) / 2;
		int composerY = (this.height - composerHeight) / 2;
		composer = new DraftComposerWidget(
			composerX, composerY, composerWidth, composerHeight,
			pendingMedia, this::dismissComposer, this::openRecipientPicker
		);
		composer.init();
	}

	private void openRecipientPicker() {
		if (composer == null) {
			return;
		}
		showingRecipientPicker = true;
		recipientPicker = new SendRecipientsWidget(
			composerX(),
			composerY(),
			composerWidth(),
			composerHeight(),
			this::onClose,
			this::confirmSendRecipients
		);
		recipientPicker.init();
	}

	private void confirmSendRecipients(List<BlockChatSnapSender.SendTarget> recipients) {
		if (composer == null) {
			return;
		}
		if (BlockChatSnapSender.startSend(
			composer.media(),
			recipients,
			composer.captionText(),
			composer.captionOffsetY(),
			composer.expiryMs()
		)) {
			this.onClose();
		}
	}

	private void openOldestUnreadForSender(String senderUuid) {
		if (senderUuid == null || senderUuid.isBlank()) {
			return;
		}
		if (showingComposer || showingRecipientPicker) {
			return;
		}
		dismissInboxViewer();
		startInboxOpen(senderUuid);
	}

	private void startInboxOpen(String senderUuid) {
		if (loadingInboxViewer) {
			return;
		}
		CompletableFuture<BlockChatInboxState.ViewedSnap> future = BlockChatInboxState.openOldestUnreadForSender(senderUuid);
		if (future == null) {
			return;
		}
		if (future.isDone()) {
			future.whenComplete((viewedSnap, error) ->
				Minecraft.getInstance().execute(() -> completeInboxOpen(null, viewedSnap, error))
			);
			return;
		}
		loadingInboxViewer = true;
		pendingInboxOpen = future;
		future.whenComplete((viewedSnap, error) ->
			Minecraft.getInstance().execute(() -> completeInboxOpen(future, viewedSnap, error))
		);
	}

	private void completeInboxOpen(
		CompletableFuture<BlockChatInboxState.ViewedSnap> future,
		BlockChatInboxState.ViewedSnap viewedSnap,
		Throwable error
	) {
		if (future != null && pendingInboxOpen != future) {
			cleanupViewedSnapPath(viewedSnap);
			return;
		}
		pendingInboxOpen = null;
		loadingInboxViewer = false;
		if (error != null || viewedSnap == null) {
			cleanupViewedSnapPath(viewedSnap);
			return;
		}
		int margin = 20;
		int viewerWidth = Math.max(300, this.width - margin * 2);
		int viewerHeight = Math.max(220, this.height - margin * 2);
		int viewerX = (this.width - viewerWidth) / 2;
		int viewerY = (this.height - viewerHeight) / 2;
		inboxViewer = new InboxSnapViewerWidget(
			viewerX,
			viewerY,
			viewerWidth,
			viewerHeight,
			viewedSnap,
			this::advanceInboxViewer,
			this::dismissInboxViewer
		);
		inboxViewer.init();
	}

	private void advanceInboxViewer() {
		String senderUuid = inboxViewer == null ? null : inboxViewer.senderUuid();
		dismissInboxViewer();
		if (senderUuid != null && !senderUuid.isBlank()) {
			startInboxOpen(senderUuid);
		}
	}

	private void dismissInboxViewer() {
		if (inboxViewer != null) {
			inboxViewer.onClose();
			inboxViewer = null;
		}
		cancelInboxLoading();
	}

	private void cancelInboxLoading() {
		pendingInboxOpen = null;
		loadingInboxViewer = false;
	}

	private void cleanupViewedSnapPath(BlockChatInboxState.ViewedSnap viewedSnap) {
		if (viewedSnap == null || viewedSnap.localPath() == null) {
			return;
		}
		try {
			Files.deleteIfExists(viewedSnap.localPath());
		} catch (Exception ignored) {
		}
	}

	private void renderInboxLoading(GuiGraphics graphics) {
		int boxWidth = Math.min(260, this.width - 40);
		int boxHeight = 70;
		int boxX = (this.width - boxWidth) / 2;
		int boxY = (this.height - boxHeight) / 2;
		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xEE1A1A2E);
		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + 1, 0xFF4A4A6A);
		graphics.fill(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, 0xFF4A4A6A);
		graphics.fill(boxX, boxY, boxX + 1, boxY + boxHeight, 0xFF4A4A6A);
		graphics.fill(boxX + boxWidth - 1, boxY, boxX + boxWidth, boxY + boxHeight, 0xFF4A4A6A);
		graphics.drawCenteredString(this.font, BlockChatStrings.LOADING_BLOCK, boxX + boxWidth / 2, boxY + 22, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, BlockChatStrings.GENERIC_PLEASE_WAIT, boxX + boxWidth / 2, boxY + 38, 0xFFBBBBCC);
	}

	private void renderDeleteAccountModal(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int boxX = (this.width - DELETE_MODAL_W) / 2;
		int boxY = (this.height - DELETE_MODAL_H) / 2;
		int boxRight = boxX + DELETE_MODAL_W;
		int boxBottom = boxY + DELETE_MODAL_H;

		graphics.fill(boxX, boxY, boxRight, boxBottom, 0xEE241010);
		graphics.fill(boxX, boxY, boxRight, boxY + 1, 0xFFC76565);
		graphics.fill(boxX, boxBottom - 1, boxRight, boxBottom, 0xFF5C1F1F);
		graphics.fill(boxX, boxY, boxX + 1, boxBottom, 0xFFC76565);
		graphics.fill(boxRight - 1, boxY, boxRight, boxBottom, 0xFF5C1F1F);

		graphics.drawCenteredString(this.font, BlockChatStrings.DELETE_ACCOUNT_MODAL_TITLE, boxX + DELETE_MODAL_W / 2, boxY + 12, 0xFFFFB3B3);

		List<String> warningLines = wrapText(this.font, BlockChatStrings.DELETE_ACCOUNT_WARNING, DELETE_MODAL_W - 24);
		int textY = boxY + 34;
		for (String line : warningLines) {
			graphics.drawString(this.font, line, boxX + 12, textY, 0xFFFFFFFF, false);
			textY += 10;
		}

		if (deleteAccountErrorMessage != null && !deleteAccountErrorMessage.isBlank()) {
			textY += 6;
			for (String line : wrapText(this.font, deleteAccountErrorMessage, DELETE_MODAL_W - 24)) {
				graphics.drawString(this.font, line, boxX + 12, textY, 0xFFFF8C8C, false);
				textY += 10;
			}
		} else if (!deletingAccount && !BlockChatWebSocket.isConnected()) {
			textY += 6;
			for (String line : wrapText(this.font, BlockChatStrings.RECONNECT_BEFORE_DELETE, DELETE_MODAL_W - 24)) {
				graphics.drawString(this.font, line, boxX + 12, textY, 0xFFFFA7A7, false);
				textY += 10;
			}
		}

		refreshDeleteAccountModalButtons();
		BlockChatWidgetRenderers.render(cancelDeleteAccountButton, graphics, mouseX, mouseY, partialTick);
		BlockChatWidgetRenderers.render(confirmDeleteAccountButton, graphics, mouseX, mouseY, partialTick);
		if (!confirmDeleteAccountButton.active && !deletingAccount && isWithin(confirmDeleteAccountButton, mouseX, mouseY)) {
			BlockChatGuiCompat.setTooltip(
				graphics,
				this.font,
				Component.literal(BlockChatStrings.RECONNECT_BEFORE_DELETE),
				mouseX,
				mouseY
			);
		}
	}

	private void refreshDeleteAccountModalButtons() {
		if (cancelDeleteAccountButton == null || confirmDeleteAccountButton == null) {
			return;
		}
		cancelDeleteAccountButton.active = !deletingAccount;
		confirmDeleteAccountButton.active = !deletingAccount && BlockChatWebSocket.isConnected();
		confirmDeleteAccountButton.setMessage(
			Component.literal(deletingAccount ? BlockChatStrings.DELETING_BUTTON : BlockChatStrings.DELETE_ACCOUNT_BUTTON).withStyle(ChatFormatting.RED)
		);
	}

	private int composerX() {
		return composer == null ? (this.width - PANEL_W) / 2 : composerXFromWidth(composerWidth());
	}

	private int composerY() {
		return composer == null ? (this.height - PANEL_H) / 2 : (this.height - composerHeight()) / 2;
	}

	private int composerWidth() {
		return composer == null ? PANEL_W : composerWidthFromPending();
	}

	private int composerHeight() {
		return Math.max(280, this.height - 40);
	}

	private int composerXFromWidth(int width) {
		return (this.width - width) / 2;
	}

	private int composerWidthFromPending() {
		if (pendingMedia == null) {
			return PANEL_W;
		}
		int margin = 20;
		int maxOuterW = this.width - margin * 2;
		int mediaW = pendingMedia.videoWidth();
		int mediaH = pendingMedia.videoHeight();
		if (pendingMedia.type() == PendingCapture.MediaType.IMAGE) {
			try (InputStream is = Files.newInputStream(pendingMedia.path());
				 NativeImage probe = NativeImage.read(is)) {
				if (probe != null) {
					mediaW = probe.getWidth();
					mediaH = probe.getHeight();
				}
			} catch (Exception ignored) {
				mediaW = 0;
				mediaH = 0;
			}
		}
		return DraftComposerWidget.suggestComposerWidth(composerHeight(), mediaW, mediaH, maxOuterW);
	}

	private boolean dispatchMouseClickedToActiveTab(MouseButtonEvent event, boolean fromSelf) {
		if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
			return false;
		}
		return tabs.get(activeTabIndex).mouseClicked(event, fromSelf);
	}

	private boolean dispatchKeyPressedToActiveTab(KeyEvent event) {
		if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
			return false;
		}
		return tabs.get(activeTabIndex).keyPressed(event);
	}

	private boolean dispatchCharTypedToActiveTab(CharacterEvent event) {
		if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
			return false;
		}
		return tabs.get(activeTabIndex).charTyped(event);
	}

	private boolean activeTabBlocksOpenKeyClose() {
		if (showingComposer || activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
			return false;
		}
		return tabs.get(activeTabIndex).blocksOpenKeyClose();
	}

	private boolean isWithin(Button button, int mouseX, int mouseY) {
		return button != null
			&& mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
			&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
	}
}
