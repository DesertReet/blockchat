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

	@Inject(
		method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
		at = @At("HEAD")
	)
	private static void blockchat$onPlayerGrabStarting(
		File gameDir,
		String fileName,
		RenderTarget renderTarget,
		Consumer<Component> messageCallback,
		CallbackInfo ci
	) {
		if (fileName != null) {
			return;
		}
		PlayerScreenshotCaptureSupport.onPlayerGrabStarting();
	}

	@Inject(
		method = "method_1661(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/io/File;Ljava/util/function/Consumer;)V",
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
