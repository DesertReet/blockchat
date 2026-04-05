package desertreet.blockchat.mixin;

import desertreet.blockchat.capture.VideoRecorder;
import desertreet.blockchat.compat.BlockChatRenderSystemCompat;
import desertreet.blockchat.hud.BlockChatHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;flush()V",
			shift = At.Shift.BEFORE
		)
	)
	private void blockchat$beforeGuiFlush(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		VideoRecorder.onFrameRendered();
		if (!VideoRecorder.isRecording()) {
			return;
		}
		GuiGraphics overlayGraphics = new GuiGraphics(this.minecraft, this.minecraft.renderBuffers().bufferSource());
		BlockChatHud.renderRecordingIndicator(overlayGraphics);
		overlayGraphics.flush();
		BlockChatRenderSystemCompat.drainPendingTasks();
	}
}
