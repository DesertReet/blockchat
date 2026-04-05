package desertreet.blockchat.ui;

import desertreet.blockchat.compat.BlockChatLegacyInputs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		if (blockchatMouseClicked(new BlockChatLegacyInputs.MouseButton(event.x(), event.y(), event.button(), event.modifiers()), fromSelf)) {
			return true;
		}
		return super.mouseClicked(event, fromSelf);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (blockchatMouseDragged(new BlockChatLegacyInputs.MouseButton(event.x(), event.y(), event.button(), event.modifiers()), dragX, dragY)) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (blockchatMouseReleased(new BlockChatLegacyInputs.MouseButton(event.x(), event.y(), event.button(), event.modifiers()))) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (blockchatKeyPressed(new BlockChatLegacyInputs.Key(event.key(), event.scancode(), event.modifiers()))) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (blockchatCharTyped(new BlockChatLegacyInputs.Character(event.codepoint(), event.modifiers()))) {
			return true;
		}
		return super.charTyped(event);
	}
}
