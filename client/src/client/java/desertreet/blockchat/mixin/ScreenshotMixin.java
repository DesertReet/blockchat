package desertreet.blockchat.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.auth.BlockChatAuth;
import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.hud.BlockChatHud;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

	/**
	 * Player F2 uses {@code grab(..., fileName=null, downscale=1)}. Clear prior image pending before
	 * vanilla schedules capture; does not touch the {@code takeScreenshot} consumer.
	 */
	@Inject(
		method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
		at = @At("HEAD")
	)
	private static void blockchat$onPlayerGrabStarting(
		File gameDir,
		@Nullable String fileName,
		RenderTarget renderTarget,
		int downscale,
		Consumer<Component> messageCallback,
		CallbackInfo ci
	) {
		if (fileName != null || downscale != 1) {
			return;
		}
		if (!BlockChatAuth.isLoggedIn()) {
			PendingCapture.onPlayerScreenshotGrabStarting();
			return;
		}
		PendingCapture.onPlayerScreenshotGrabStarting();
	}

	/**
	 * Vanilla routes completed pixels here before async disk write. Copy the same {@link NativeImage}
	 * for BlockChat pending; vanilla path is unchanged.
	 */
	@Inject(
		method = "method_68157(Ljava/io/File;Ljava/lang/String;Ljava/util/function/Consumer;Lcom/mojang/blaze3d/platform/NativeImage;)V",
		at = @At("HEAD")
	)
	private static void blockchat$savePendingFromVanillaCapture(
		File gameDirectory,
		@Nullable String fileName,
		Consumer<Component> messageCallback,
		NativeImage image,
		CallbackInfo ci
	) {
		if (fileName != null || !BlockChatAuth.isLoggedIn()) {
			return;
		}
		try {
			Path pending = BlockChatPaths.getRuntimePath("_pending_screenshot.png");
			BlockChatPaths.ensureRuntimeDirectory(pending.getParent());
			try (NativeImage copy = new NativeImage(image.format(), image.getWidth(), image.getHeight(), false)) {
				copy.copyFrom(image);
				copy.writeToFile(pending);
			}
			BlockChatPaths.ensureHiddenBestEffort(pending);
			PendingCapture.setPendingPlayerScreenshot(pending);
			BlockChatHud.showCaptureToast(
				Component.literal(BlockChatStrings.SCREENSHOT_CAPTURED),
				Component.literal(
					BlockChatStrings.pressKeySoonToSendInBlockChat(BlockChatClientMod.getOpenKeyDisplayName())
				)
			);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("BlockChat could not save pending screenshot", e);
		}
	}
}
