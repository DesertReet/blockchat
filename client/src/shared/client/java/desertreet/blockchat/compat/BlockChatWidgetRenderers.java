package desertreet.blockchat.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

public final class BlockChatWidgetRenderers {

	private BlockChatWidgetRenderers() {
	}

	public static void render(AbstractWidget widget, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (widget == null || graphics == null) {
			return;
		}
		widget.render(graphics, mouseX, mouseY, partialTick);
	}
}
