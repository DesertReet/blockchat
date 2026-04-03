package desertreet.blockchat.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.capture.CaptureDiagnostics;
import desertreet.blockchat.capture.MicrophoneDeviceManager;
import desertreet.blockchat.capture.MicrophoneDeviceManager.MicrophoneDevice;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.preferences.BlockChatPreferenceState;
import desertreet.blockchat.skin.PlayerLookupCache;
import desertreet.blockchat.skin.SkinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SettingsTab implements BlockChatTab {

	private int x, y, width, height;
	private Button notificationsButton;
	private Button friendRequestsButton;
	private Button micEnabledButton;
	private Button micDeviceButton;
	private Button recordingDiagnosticsButton;
	private Button signOutButton;
	private Button deleteAccountButton;
	private final List<GuiEventListener> listeners = new ArrayList<>();
	private final Runnable openDeleteAccountModal;

	public SettingsTab(Runnable openDeleteAccountModal) {
		this.openDeleteAccountModal = openDeleteAccountModal == null ? () -> { } : openDeleteAccountModal;
	}

	@Override
	public String getTitle() {
		return BlockChatStrings.SETTINGS_TAB;
	}

	@Override
	public void init(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		listeners.clear();

		int buttonWidth = Math.min(200, width - 20);
		int centerX = x + (width - buttonWidth) / 2;
		int rowY = y + 26;
		int rowSpacing = 20;

		notificationsButton = Button.builder(
			Component.empty(),
			b -> toggleNotifications()
		).bounds(centerX, rowY, buttonWidth, 20).build();
		listeners.add(notificationsButton);

		friendRequestsButton = Button.builder(
			Component.empty(),
			b -> toggleFriendRequests()
		).bounds(centerX, rowY + rowSpacing, buttonWidth, 20).build();
		listeners.add(friendRequestsButton);

		micEnabledButton = Button.builder(
			Component.empty(),
			b -> toggleMicEnabled()
		).bounds(centerX, rowY + rowSpacing * 2, buttonWidth, 20).build();
		listeners.add(micEnabledButton);

		micDeviceButton = Button.builder(
			Component.empty(),
			b -> cycleMicDevice()
		).bounds(centerX, rowY + rowSpacing * 3, buttonWidth, 20).build();
		listeners.add(micDeviceButton);

		recordingDiagnosticsButton = Button.builder(
			Component.empty(),
			b -> toggleRecordingDiagnostics()
		).bounds(centerX, rowY + rowSpacing * 4, buttonWidth, 20).build();
		listeners.add(recordingDiagnosticsButton);

		if (BlockChatAuth.isLoggedIn()) {
			signOutButton = Button.builder(
				Component.literal(BlockChatStrings.SIGN_OUT),
				b -> onSignOut()
			).bounds(centerX, rowY + rowSpacing * 5, buttonWidth, 20).build();
			listeners.add(signOutButton);

			deleteAccountButton = Button.builder(
				Component.literal(BlockChatStrings.DELETE_MY_ACCOUNT_BUTTON).withStyle(ChatFormatting.RED),
				b -> onDeleteAccount()
			).bounds(centerX, rowY + rowSpacing * 6, buttonWidth, 20).build();
			listeners.add(deleteAccountButton);
		} else {
			signOutButton = null;
			deleteAccountButton = null;
		}

		refreshServerButtons();
		refreshMicButtons();
		refreshAccountButtons();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		// Render player face and name centered at top, or just "Settings" if not logged in
		if (BlockChatAuth.isLoggedIn()) {
			renderPlayerIdentity(graphics, font);
		} else {
			graphics.drawCenteredString(font, BlockChatStrings.SETTINGS_TAB, x + width / 2, y + 8, 0xFFFFFF);
		}

		refreshServerButtons();
		notificationsButton.render(graphics, mouseX, mouseY, partialTick);
		friendRequestsButton.render(graphics, mouseX, mouseY, partialTick);
		refreshMicButtons();
		micEnabledButton.render(graphics, mouseX, mouseY, partialTick);
		micDeviceButton.render(graphics, mouseX, mouseY, partialTick);
		refreshRecordingDiagnosticsButton();
		recordingDiagnosticsButton.render(graphics, mouseX, mouseY, partialTick);

		if (signOutButton != null && BlockChatAuth.isLoggedIn()) {
			signOutButton.render(graphics, mouseX, mouseY, partialTick);
		}
		refreshAccountButtons();
		if (deleteAccountButton != null && BlockChatAuth.isLoggedIn()) {
			deleteAccountButton.render(graphics, mouseX, mouseY, partialTick);
			if (!deleteAccountButton.active) {
				graphics.drawCenteredString(
					font,
					BlockChatStrings.RECONNECT_TO_DELETE_ACCOUNT,
					x + width / 2,
					deleteAccountButton.getY() + deleteAccountButton.getHeight() + 4,
					0xFFFFBBBB
				);
			}
		}

		renderButtonTooltip(graphics, font, mouseX, mouseY);
	}

	private void renderPlayerIdentity(GuiGraphics graphics, Font font) {
		String username = BlockChatConfig.getUsername();
		String skinUrl = BlockChatConfig.getSkinUrl();
		String uuid = BlockChatConfig.getUserUuid();

		int faceSize = 16; // render the 8x8 face at 16x16
		int padding = 4;
		int nameWidth = font.width(username);
		int centerX = x + width / 2;
		int drawY = y + 6;

		// Try to get the face texture (merge config + PlayerLookupCache)
		String resolvedSkin = PlayerLookupCache.resolveSkinUrl(username, uuid, skinUrl);
		Identifier faceTexture = SkinHelper.getFaceTexture(resolvedSkin, uuid);
		if (faceTexture != null) {
			int totalWidth = faceSize + padding + nameWidth;
			int drawX = centerX - totalWidth / 2;
			graphics.drawString(font, username, drawX + faceSize + padding, drawY + (faceSize - font.lineHeight) / 2 + 1, 0xFFFFFFFF, true);
			graphics.blit(RenderPipelines.GUI_TEXTURED, faceTexture, drawX, drawY, 0, 0, faceSize, faceSize, faceSize, faceSize);
		} else {
			graphics.drawCenteredString(font, username, centerX, drawY + 1, 0xFFFFFFFF);
		}
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return listeners;
	}

	private void toggleNotifications() {
		BlockChatPreferenceState.setNotificationsEnabled(!BlockChatPreferenceState.isNotificationsEnabled());
		refreshServerButtons();
	}

	private void toggleFriendRequests() {
		BlockChatPreferenceState.setFriendRequestsEnabled(!BlockChatPreferenceState.isFriendRequestsEnabled());
		refreshServerButtons();
	}

	private void toggleMicEnabled() {
		BlockChatConfig.setMicEnabled(!BlockChatConfig.isMicEnabled());
		BlockChatConfig.save();
		refreshMicButtons();
	}

	private void toggleRecordingDiagnostics() {
		BlockChatConfig.setRecordingDiagnosticsEnabled(!BlockChatConfig.isRecordingDiagnosticsEnabled());
		BlockChatConfig.save();
		CaptureDiagnostics.setEnabled(BlockChatConfig.isRecordingDiagnosticsEnabled());
		refreshRecordingDiagnosticsButton();
	}

	private void onSignOut() {
		// Revoke session via WebSocket if connected, then disconnect
		if (BlockChatWebSocket.isConnected()) {
			BlockChatWebSocket.revokeSession();
		}
		BlockChatWebSocket.disconnect();
		BlockChatAuth.logout();

		// Force screen rebuild to show login UI
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof BlockChatScreen bcs) {
			bcs.rebuildFromTab();
		}
	}

	private void onDeleteAccount() {
		if (!BlockChatWebSocket.isConnected()) {
			return;
		}
		openDeleteAccountModal.run();
	}

	private void cycleMicDevice() {
		List<MicrophoneDevice> devices = MicrophoneDeviceManager.getDevices();
		if (devices.isEmpty()) {
			return;
		}

		String preferredId = BlockChatConfig.getPreferredMicDeviceId();
		String preferredName = BlockChatConfig.getPreferredMicDeviceName();
		int currentIndex = indexOfPreferred(devices, preferredId, preferredName);
		int optionCount = devices.size() + 1; // explicit "System default" slot
		int nextIndex = (currentIndex + 1 + optionCount) % optionCount;
		if (nextIndex == devices.size()) {
			BlockChatConfig.setPreferredMicDeviceId("");
			BlockChatConfig.setPreferredMicDeviceName("");
			BlockChatConfig.save();
			refreshMicButtons();
			return;
		}
		MicrophoneDevice device = devices.get(nextIndex);
		BlockChatConfig.setPreferredMicDeviceId(device.id());
		BlockChatConfig.setPreferredMicDeviceName(device.displayName());
		BlockChatConfig.save();
		refreshMicButtons();
	}

	private int indexOfPreferred(List<MicrophoneDevice> devices, String preferredId, String preferredName) {
		if (preferredId.isBlank() && preferredName.isBlank()) {
			return devices.size();
		}
		for (int i = 0; i < devices.size(); i++) {
			if (!preferredId.isBlank() && devices.get(i).id().equals(preferredId)) {
				return i;
			}
		}
		for (int i = 0; i < devices.size(); i++) {
			if (!preferredName.isBlank() && devices.get(i).displayName().equals(preferredName)) {
				return i;
			}
		}
		return -1;
	}

	private void refreshMicButtons() {
		if (micEnabledButton == null || micDeviceButton == null) {
			return;
		}

		micEnabledButton.setMessage(Component.literal(
			BlockChatStrings.micRecording(BlockChatConfig.isMicEnabled())
		));

		String deviceLabel;
		if (MicrophoneDeviceManager.isDetectionInProgress()) {
			deviceLabel = BlockChatStrings.MIC_DETECTING;
			micDeviceButton.active = false;
		} else {
			List<MicrophoneDevice> devices = MicrophoneDeviceManager.getDevices();
			Optional<MicrophoneDevice> preferred = MicrophoneDeviceManager.findPreferredDevice(
				BlockChatConfig.getPreferredMicDeviceId(),
				BlockChatConfig.getPreferredMicDeviceName()
			);
			if (preferred.isPresent()) {
				deviceLabel = shorten(preferred.get().displayName(), 22);
				micDeviceButton.active = true;
			} else if (!devices.isEmpty()) {
				boolean hasSavedPreference = !BlockChatConfig.getPreferredMicDeviceId().isBlank()
					|| !BlockChatConfig.getPreferredMicDeviceName().isBlank();
				deviceLabel = hasSavedPreference ? BlockChatStrings.MIC_SAVED_MISSING : BlockChatStrings.MIC_SYSTEM_DEFAULT;
				micDeviceButton.active = true;
			} else {
				deviceLabel = BlockChatStrings.MIC_NO_DEVICES_FOUND;
				micDeviceButton.active = false;
			}
		}
		micDeviceButton.setMessage(Component.literal(BlockChatStrings.micInput(deviceLabel)));
	}

	private void refreshRecordingDiagnosticsButton() {
		if (recordingDiagnosticsButton == null) {
			return;
		}
		recordingDiagnosticsButton.setMessage(Component.literal(
			BlockChatStrings.recordingDiagnostics(BlockChatConfig.isRecordingDiagnosticsEnabled())
		));
		recordingDiagnosticsButton.active = true;
	}

	private void refreshServerButtons() {
		if (notificationsButton == null || friendRequestsButton == null) {
			return;
		}

		notificationsButton.setMessage(Component.literal(
			BlockChatStrings.notifications(BlockChatPreferenceState.isNotificationsEnabled())
		));
		friendRequestsButton.setMessage(Component.literal(
			BlockChatStrings.friendRequestsEnabled(BlockChatPreferenceState.isFriendRequestsEnabled())
		));

		boolean active = BlockChatWebSocket.isConnected() && BlockChatPreferenceState.isLoaded();
		notificationsButton.active = active;
		friendRequestsButton.active = active;
	}

	private void refreshAccountButtons() {
		if (deleteAccountButton == null) {
			return;
		}
		deleteAccountButton.active = BlockChatWebSocket.isConnected();
		deleteAccountButton.setMessage(Component.literal(BlockChatStrings.DELETE_MY_ACCOUNT_BUTTON).withStyle(ChatFormatting.RED));
	}

	private void renderButtonTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
		String tooltip = null;

		if (isHovered(notificationsButton, mouseX, mouseY)) {
			tooltip = BlockChatPreferenceState.isNotificationsEnabled()
				? BlockChatStrings.SETTINGS_TOOLTIP_NOTIFICATIONS_ON
				: BlockChatStrings.SETTINGS_TOOLTIP_NOTIFICATIONS_OFF;
		} else if (isHovered(friendRequestsButton, mouseX, mouseY)) {
			tooltip = BlockChatPreferenceState.isFriendRequestsEnabled()
				? BlockChatStrings.SETTINGS_TOOLTIP_FRIEND_REQUESTS_ON
				: BlockChatStrings.SETTINGS_TOOLTIP_FRIEND_REQUESTS_OFF;
		} else if (isHovered(micEnabledButton, mouseX, mouseY)) {
			tooltip = BlockChatConfig.isMicEnabled()
				? BlockChatStrings.SETTINGS_TOOLTIP_MIC_ON
				: BlockChatStrings.SETTINGS_TOOLTIP_MIC_OFF;
		} else if (isHovered(recordingDiagnosticsButton, mouseX, mouseY)) {
			tooltip = BlockChatConfig.isRecordingDiagnosticsEnabled()
				? BlockChatStrings.SETTINGS_TOOLTIP_RECORDING_DIAGNOSTICS_ON
				: BlockChatStrings.SETTINGS_TOOLTIP_RECORDING_DIAGNOSTICS_OFF;
		} else if (isHoveredAnyState(deleteAccountButton, mouseX, mouseY)) {
			tooltip = deleteAccountButton.active
				? BlockChatStrings.SETTINGS_TOOLTIP_DELETE_ACCOUNT
				: BlockChatStrings.RECONNECT_BEFORE_DELETE;
		}

		if (tooltip != null) {
			graphics.setTooltipForNextFrame(font, Component.literal(tooltip), mouseX, mouseY);
		}
	}

	private boolean isHovered(Button button, int mouseX, int mouseY) {
		return button != null && button.visible && button.active
			&& mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
			&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
	}

	private boolean isHoveredAnyState(Button button, int mouseX, int mouseY) {
		return button != null && button.visible
			&& mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
			&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
	}

	private String shorten(String value, int maxLength) {
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, Math.max(0, maxLength - 3)) + "...";
	}
}
