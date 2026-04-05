package desertreet.blockchat.ui;

import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.compat.BlockChatLegacyInputs;
import net.minecraft.client.gui.GuiGraphics;

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

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		BlockChatLegacyInputs.MouseButton event = new BlockChatLegacyInputs.MouseButton(mouseX, mouseY, button, 0);
		if (blockchatMouseClicked(event, true)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		BlockChatLegacyInputs.MouseButton event = new BlockChatLegacyInputs.MouseButton(mouseX, mouseY, button, 0);
		if (blockchatMouseDragged(event, dragX, dragY)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		BlockChatLegacyInputs.MouseButton event = new BlockChatLegacyInputs.MouseButton(mouseX, mouseY, button, 0);
		if (blockchatMouseReleased(event)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		BlockChatLegacyInputs.Key event = new BlockChatLegacyInputs.Key(keyCode, scanCode, modifiers);
		if (blockchatKeyPressed(event)) {
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		BlockChatLegacyInputs.Character event = new BlockChatLegacyInputs.Character(codePoint, modifiers);
		if (blockchatCharTyped(event)) {
			return true;
		}
		return super.charTyped(codePoint, modifiers);
	}
}
