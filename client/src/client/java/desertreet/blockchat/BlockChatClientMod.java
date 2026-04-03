package desertreet.blockchat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.capture.FFmpegLocator;
import desertreet.blockchat.capture.CaptureDiagnostics;
import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.capture.MicrophoneDeviceManager;
import desertreet.blockchat.capture.VideoRecorder;
import desertreet.blockchat.capture.macos.MacOsCaptureHelperLocator;
import desertreet.blockchat.capture.windows.WindowsNativeCaptureHelperLocator;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.hud.BlockChatHud;
import desertreet.blockchat.preferences.BlockChatPreferenceState;
import desertreet.blockchat.ui.BlockChatScreen;
import desertreet.blockchat.util.BlockChatPaths;

public final class BlockChatClientMod implements ClientModInitializer {
	public static final String MOD_ID = "desertreet-blockchat";
	public static final Logger LOGGER = LoggerFactory.getLogger("BlockChat");

	private static final KeyMapping.Category KEY_CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("desertreet", "blockchat"));

	private static KeyMapping openKey;
	private static KeyMapping recordKey;

	@Override
	public void onInitializeClient() {
		BlockChatPaths.prepareRuntimeLayout();
		BlockChatConfig.load();
		CaptureDiagnostics.setEnabled(BlockChatConfig.isRecordingDiagnosticsEnabled());
		BlockChatPreferenceState.installWebSocketListener();

		// Sign out if the Minecraft account doesn't match the stored BlockChat account
		if (BlockChatConfig.isLoggedIn()) {
			String mcUsername = Minecraft.getInstance().getUser().getName();
			String storedUsername = BlockChatConfig.getUsername();
			if (!storedUsername.isEmpty() && !storedUsername.equals(mcUsername)) {
				LOGGER.info("Minecraft username '{}' does not match stored BlockChat username '{}', signing out",
					mcUsername, storedUsername);
				BlockChatConfig.clearSession();
			}
		}

		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.blockchat.open",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_U,
			KEY_CATEGORY
		));

		recordKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.blockchat.record",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_J,
			KEY_CATEGORY
		));

		BlockChatHud.register();
		BlockChatClientWarmup.register();

		FFmpegLocator.detectAsync();
		MicrophoneDeviceManager.detectAsync();
		MacOsCaptureHelperLocator.prepareBundledHelperAsync();
		WindowsNativeCaptureHelperLocator.prepareBundledHelperAsync();

		ClientTickEvents.END_CLIENT_TICK.register(BlockChatClientMod::onEndClientTick);

		LOGGER.info("BlockChat initialized");
	}

	private static void onEndClientTick(Minecraft client) {
		BlockChatClientWarmup.tick(client);
		BlockChatHud.tick();

		while (openKey.consumeClick()) {
			if (BlockChatHud.isCaptureToastActive()) {
				BlockChatHud.dismissCaptureToast();
			}
			client.setScreen(new BlockChatScreen(PendingCapture.consumeIfReady()));
		}

		while (recordKey.consumeClick()) {
			if (client.screen != null || !BlockChatAuth.isLoggedIn()) {
				continue;
			}
			VideoRecorder.toggle(client);
		}
	}

	public static KeyMapping getOpenKey() {
		return openKey;
	}

	public static String getOpenKeyDisplayName() {
		try {
			if (openKey != null) {
				return openKey.getTranslatedKeyMessage().getString();
			}
		} catch (Exception ignored) {
		}
		return BlockChatStrings.DEFAULT_OPEN_KEY_NAME;
	}
}
