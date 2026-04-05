package desertreet.blockchat.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;

public final class BlockChatToastCompat {

	private BlockChatToastCompat() {
	}

	public static Object getToastHost(Minecraft client) {
		return client == null ? null : client.getToastManager();
	}

	public static void addSystemToast(Object toastHost, SystemToast.SystemToastId id, Component title, Component subtitle) {
		if (toastHost instanceof ToastManager manager) {
			SystemToast.add(manager, id, title, subtitle);
		}
	}

	public static void forceHideSystemToast(Object toastHost, SystemToast.SystemToastId id) {
		if (toastHost instanceof ToastManager manager) {
			SystemToast.forceHide(manager, id);
		}
	}

	public static boolean hasToast(Object toastHost, Class<? extends Toast> toastClass, Object token) {
		return toastHost instanceof ToastManager manager && manager.getToast(toastClass, token) != null;
	}

	public static void addToast(Object toastHost, Toast toast) {
		if (toastHost instanceof ToastManager manager && toast != null) {
			manager.addToast(toast);
		}
	}
}
