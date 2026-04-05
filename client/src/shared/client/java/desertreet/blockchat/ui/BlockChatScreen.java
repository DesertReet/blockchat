package desertreet.blockchat.ui;

import net.minecraft.client.gui.GuiGraphics;
import desertreet.blockchat.capture.PendingCapture;

public final class BlockChatScreen extends BlockChatScreenBase {

	public BlockChatScreen(PendingCapture.PendingMedia pendingMedia) {
		super(pendingMedia);
	}

	@Override
	protected void renderMenuBackgroundCompat(GuiGraphics graphics) {
		this.renderMenuBackground(graphics);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBlockChatScreen(graphics, mouseX, mouseY, partialTick);
	}
}
