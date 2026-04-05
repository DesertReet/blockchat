package desertreet.blockchat.compat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

public final class BlockChatWidgetInputs {

	private BlockChatWidgetInputs() {
	}

	public static boolean mouseClicked(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event, boolean fromSelf) {
		if (listener == null || event == null) {
			return false;
		}
		return listener.mouseClicked(mouseEvent(event), fromSelf);
	}

	public static boolean mouseReleased(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event) {
		if (listener == null || event == null) {
			return false;
		}
		return listener.mouseReleased(mouseEvent(event));
	}

	public static boolean mouseDragged(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event, double dragX, double dragY) {
		if (listener == null || event == null) {
			return false;
		}
		return listener.mouseDragged(mouseEvent(event), dragX, dragY);
	}

	public static boolean keyPressed(GuiEventListener listener, BlockChatLegacyInputs.Key event) {
		if (listener == null || event == null) {
			return false;
		}
		return listener.keyPressed(new KeyEvent(event.key(), event.scancode(), event.modifiers()));
	}

	public static boolean charTyped(GuiEventListener listener, BlockChatLegacyInputs.Character event) {
		if (listener == null || event == null) {
			return false;
		}
		return listener.charTyped(new CharacterEvent(event.codepoint(), event.modifiers()));
	}

	public static boolean matchesKey(KeyMapping mapping, BlockChatLegacyInputs.Key event) {
		if (mapping == null || event == null) {
			return false;
		}
		return mapping.matches(new KeyEvent(event.key(), event.scancode(), event.modifiers()));
	}

	private static MouseButtonEvent mouseEvent(BlockChatLegacyInputs.MouseButton event) {
		return new MouseButtonEvent(
			event.x(),
			event.y(),
			new MouseButtonInfo(event.button(), event.modifiers())
		);
	}
}
