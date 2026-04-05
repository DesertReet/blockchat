package desertreet.blockchat.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import desertreet.blockchat.capture.PlayerScreenshotCaptureSupport;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

	/**
	 * Minecraft 26.1 keyboard handling should remain completely vanilla. We only arm BlockChat when
	 * vanilla has already entered the player screenshot path.
	 */
	@Inject(
		method = "grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
		at = @At("HEAD")
	)
	private static void blockchat$onVanillaPlayerScreenshotStarting(
		File gameDir,
		RenderTarget renderTarget,
		Consumer<Component> messageCallback,
		CallbackInfo ci
	) {
		PlayerScreenshotCaptureSupport.onPlayerGrabStarting();
	}

	/**
	 * Mirror the actual file vanilla wrote into {@code screenshots/}, after the async save worker
	 * finishes, so BlockChat drafts/notifications are driven by the completed screenshot file.
	 */
	@Inject(
		method = "lambda$grab$1(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/io/File;Ljava/util/function/Consumer;)V",
		at = @At("TAIL")
	)
	private static void blockchat$mirrorSavedVanillaScreenshot(
		NativeImage image,
		File screenshotFile,
		Consumer<Component> messageCallback,
		CallbackInfo ci
	) {
		PlayerScreenshotCaptureSupport.mirrorSavedScreenshotIfArmed(screenshotFile);
	}
}
