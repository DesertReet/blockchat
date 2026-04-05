package desertreet.blockchat.compat;

import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.reflect.Method;

public final class BlockChatRenderSystemCompat {

	private BlockChatRenderSystemCompat() {
	}

	public static void drainPendingTasks() {
		if (invoke("executePendingTasks")) {
			return;
		}
		invoke("replayQueue");
	}

	private static boolean invoke(String methodName) {
		try {
			Method method = RenderSystem.class.getMethod(methodName);
			method.invoke(null);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}
}
