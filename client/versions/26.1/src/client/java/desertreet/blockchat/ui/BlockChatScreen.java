package desertreet.blockchat.ui;

import desertreet.blockchat.capture.PendingCapture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class BlockChatScreen extends BlockChatScreenBase {

	public BlockChatScreen(PendingCapture.PendingMedia pendingMedia) {
		super(pendingMedia);
	}

	@Override
	protected void renderMenuBackgroundCompat(GuiGraphics graphics) {
		this.extractMenuBackground(graphics.unwrap());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		renderBlockChatScreen(new GuiGraphics(graphics), mouseX, mouseY, partialTick);
	}
}
