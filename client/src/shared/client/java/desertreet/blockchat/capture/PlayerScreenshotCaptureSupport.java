package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.hud.BlockChatHud;
import desertreet.blockchat.util.BlockChatPaths;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors the final vanilla screenshot file into BlockChat's pending capture slot.
 *
 * <p>This intentionally copies the file after Minecraft writes it to {@code screenshots/} instead
 * of serializing the transient framebuffer image. Newer 1.21.x builds can hand us a capture that
 * does not match the finished screenshot on disk, which produced the "blue sky only" draft image.
 */
public final class PlayerScreenshotCaptureSupport {

	private static final AtomicBoolean PLAYER_SCREENSHOT_SAVE_ARMED = new AtomicBoolean(false);

	private PlayerScreenshotCaptureSupport() {
	}

	public static void onPlayerGrabStarting() {
		PendingCapture.onPlayerScreenshotGrabStarting();
		PLAYER_SCREENSHOT_SAVE_ARMED.set(true);
	}

	public static void mirrorSavedScreenshotIfArmed(File screenshotFile) {
		if (!PLAYER_SCREENSHOT_SAVE_ARMED.getAndSet(false)) {
			return;
		}
		if (!BlockChatAuth.isLoggedIn() || screenshotFile == null) {
			return;
		}
		try {
			Path source = screenshotFile.toPath();
			if (!Files.isRegularFile(source)) {
				return;
			}
			Path pending = BlockChatPaths.getRuntimePath("_pending_screenshot.png");
			BlockChatPaths.ensureRuntimeDirectory(pending.getParent());
			Files.copy(source, pending, StandardCopyOption.REPLACE_EXISTING);
			BlockChatPaths.ensureHiddenBestEffort(pending);
			PendingCapture.setPendingPlayerScreenshot(pending);
			BlockChatHud.showCaptureToast(
				Component.literal(BlockChatStrings.SCREENSHOT_CAPTURED),
				Component.literal(
					BlockChatStrings.pressKeySoonToSendInBlockChat(BlockChatClientMod.getOpenKeyDisplayName())
				)
			);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("BlockChat could not mirror saved screenshot {}", screenshotFile, e);
		}
	}
}
