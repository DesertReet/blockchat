package desertreet.blockchat.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

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

	default boolean mouseClicked(MouseButtonEvent event, boolean fromSelf) {
		for (GuiEventListener listener : children()) {
			if (listener.mouseClicked(event, fromSelf)) {
				return true;
			}
		}
		return false;
	}

	default boolean keyPressed(KeyEvent event) {
		for (GuiEventListener listener : children()) {
			if (listener.keyPressed(event)) {
				return true;
			}
		}
		return false;
	}

	default boolean charTyped(CharacterEvent event) {
		for (GuiEventListener listener : children()) {
			if (listener.charTyped(event)) {
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
