package desertreet.blockchat.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class FriendProfileToast extends FriendProfileToastBase {

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl) {
		super(title, subtitle, uuid, skinUrl);
	}

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl, int borderColor) {
		super(title, subtitle, uuid, skinUrl, borderColor);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleFor) {
		renderToast(new GuiGraphics(graphics), font, fullyVisibleFor);
	}
}
