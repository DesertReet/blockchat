package desertreet.blockchat.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import desertreet.blockchat.capture.VideoRecorder;
import desertreet.blockchat.hud.BlockChatHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
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

	@Shadow
	@Final
	private FogRenderer fogRenderer;

	@Shadow
	@Final
	private GuiRenderer guiRenderer;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
			shift = At.Shift.AFTER
		)
	)
	private void blockchat$afterMainGuiFlush(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		VideoRecorder.onFrameRendered();
		if (!VideoRecorder.isRecording()) {
			return;
		}
		int mouseX = (int) this.minecraft.mouseHandler.getScaledXPos(this.minecraft.getWindow());
		int mouseY = (int) this.minecraft.mouseHandler.getScaledYPos(this.minecraft.getWindow());
		GuiGraphics overlayGraphics = new GuiGraphics(this.minecraft, this.gameRenderState.guiRenderState, mouseX, mouseY);
		BlockChatHud.renderRecordingIndicator(overlayGraphics);
		GpuBufferSlice slice = this.fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
		this.guiRenderer.render(slice);
		RenderSystem.executePendingTasks();
	}
}
