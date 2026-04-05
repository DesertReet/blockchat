package desertreet.blockchat.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import desertreet.blockchat.capture.PlayerScreenshotCaptureSupport;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
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
		PlayerScreenshotCaptureSupport.onPlayerGrabStarting();
	}

	/**
	 * Modern 1.21.x/26.x builds write the screenshot asynchronously in this worker. Mirror the
	 * finished file into BlockChat's pending draft slot after vanilla completes the write.
	 */
	@Inject(
		method = "method_22691(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/io/File;Ljava/util/function/Consumer;)V",
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
