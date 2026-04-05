package desertreet.blockchat.compat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.events.GuiEventListener;

public final class BlockChatWidgetInputs {

	private BlockChatWidgetInputs() {
	}

	public static boolean mouseClicked(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event, boolean fromSelf) {
		return listener != null && event != null && listener.mouseClicked(event.x(), event.y(), event.button());
	}

	public static boolean mouseReleased(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event) {
		return listener != null && event != null && listener.mouseReleased(event.x(), event.y(), event.button());
	}

	public static boolean mouseDragged(GuiEventListener listener, BlockChatLegacyInputs.MouseButton event, double dragX, double dragY) {
		return listener != null
			&& event != null
			&& listener.mouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
	}

	public static boolean keyPressed(GuiEventListener listener, BlockChatLegacyInputs.Key event) {
		return listener != null
			&& event != null
			&& listener.keyPressed(event.key(), event.scancode(), event.modifiers());
	}

	public static boolean charTyped(GuiEventListener listener, BlockChatLegacyInputs.Character event) {
		return listener != null
			&& event != null
			&& listener.charTyped(event.legacyCharacter(), event.modifiers());
	}

	public static boolean matchesKey(KeyMapping mapping, BlockChatLegacyInputs.Key event) {
		return mapping != null
			&& event != null
			&& mapping.matches(event.key(), event.scancode());
	}
}
