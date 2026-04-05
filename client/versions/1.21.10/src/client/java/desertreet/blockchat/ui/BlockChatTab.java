package desertreet.blockchat.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import desertreet.blockchat.compat.BlockChatLegacyInputs;
import desertreet.blockchat.compat.BlockChatWidgetInputs;

import java.util.List;

public interface BlockChatTab {

	String getTitle();

	void init(int x, int y, int width, int height);

	void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

	default void tick() {
	}

	default List<? extends GuiEventListener> children() {
		return List.of();
	}

	default boolean mouseClicked(BlockChatLegacyInputs.MouseButton event, boolean fromSelf) {
		for (GuiEventListener listener : children()) {
			if (BlockChatWidgetInputs.mouseClicked(listener, event, fromSelf)) {
				return true;
			}
		}
		return false;
	}

	default boolean keyPressed(BlockChatLegacyInputs.Key event) {
		for (GuiEventListener listener : children()) {
			if (BlockChatWidgetInputs.keyPressed(listener, event)) {
				return true;
			}
		}
		return false;
	}

	default boolean charTyped(BlockChatLegacyInputs.Character event) {
		for (GuiEventListener listener : children()) {
			if (BlockChatWidgetInputs.charTyped(listener, event)) {
				return true;
			}
		}
		return false;
	}

	default boolean blocksOpenKeyClose() {
		return false;
	}

	default boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return false;
	}

	default void onClose() {
	}
}
