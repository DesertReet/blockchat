package desertreet.blockchat.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;

public final class FriendProfileToast extends FriendProfileToastBase {

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl) {
		super(title, subtitle, uuid, skinUrl);
	}

	public FriendProfileToast(String title, String subtitle, String uuid, String skinUrl, int borderColor) {
		super(title, subtitle, uuid, skinUrl, borderColor);
	}

	@Override
	public Visibility render(GuiGraphics graphics, ToastComponent toastComponent, long fullyVisibleFor) {
		return renderToast(graphics, toastComponent.getMinecraft().font, fullyVisibleFor);
	}
}
