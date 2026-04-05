package desertreet.blockchat.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class FriendProfileToast extends FriendProfileToastBase {

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl) {
		super(title, subtitle, uuid, skinUrl);
	}

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl, int borderColor) {
		super(title, subtitle, uuid, skinUrl, borderColor);
	}

	@Override
	public void render(GuiGraphics graphics, Font font, long fullyVisibleFor) {
		renderToast(graphics, font, fullyVisibleFor);
	}
}
