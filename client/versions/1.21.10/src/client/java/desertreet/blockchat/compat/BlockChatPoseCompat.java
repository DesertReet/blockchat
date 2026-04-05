package desertreet.blockchat.compat;

import org.joml.Matrix3x2fStack;

public final class BlockChatPoseCompat {

	private BlockChatPoseCompat() {
	}

	public static void push(Object pose) {
		if (pose instanceof Matrix3x2fStack matrixStack) {
			matrixStack.pushMatrix();
			return;
		}
		throw new IllegalStateException("Expected a Matrix3x2fStack pose");
	}

	public static void pop(Object pose) {
		if (pose instanceof Matrix3x2fStack matrixStack) {
			matrixStack.popMatrix();
			return;
		}
		throw new IllegalStateException("Expected a Matrix3x2fStack pose");
	}

	public static void translate(Object pose, float x, float y) {
		if (pose instanceof Matrix3x2fStack matrixStack) {
			matrixStack.translate(x, y);
			return;
		}
		throw new IllegalStateException("Expected a Matrix3x2fStack pose");
	}

	public static void scale(Object pose, float x, float y) {
		if (pose instanceof Matrix3x2fStack matrixStack) {
			matrixStack.scale(x, y);
			return;
		}
		throw new IllegalStateException("Expected a Matrix3x2fStack pose");
	}
}
