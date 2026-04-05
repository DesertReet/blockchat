package desertreet.blockchat.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

public final class BlockChatToastCompat {

	private BlockChatToastCompat() {
	}

	public static Object getToastHost(Minecraft client) {
		return client == null ? null : client.getToasts();
	}

	public static void addSystemToast(Object toastHost, SystemToast.SystemToastId id, Component title, Component subtitle) {
		if (toastHost instanceof ToastComponent component) {
			SystemToast.add(component, id, title, subtitle);
		}
	}

	public static void forceHideSystemToast(Object toastHost, SystemToast.SystemToastId id) {
		if (toastHost instanceof ToastComponent component) {
			SystemToast.forceHide(component, id);
		}
	}

	public static boolean hasToast(Object toastHost, Class<? extends Toast> toastClass, Object token) {
		return toastHost instanceof ToastComponent component && component.getToast(toastClass, token) != null;
	}

	public static void addToast(Object toastHost, Toast toast) {
		if (toastHost instanceof ToastComponent component && toast != null) {
			component.addToast(toast);
		}
	}
}
