package desertreet.blockchat.compat;

import com.mojang.blaze3d.vertex.PoseStack;

public final class BlockChatPoseCompat {

	private BlockChatPoseCompat() {
	}

	public static void push(Object pose) {
		if (pose instanceof PoseStack poseStack) {
			poseStack.pushPose();
			return;
		}
		throw new IllegalStateException("Expected a PoseStack pose");
	}

	public static void pop(Object pose) {
		if (pose instanceof PoseStack poseStack) {
			poseStack.popPose();
			return;
		}
		throw new IllegalStateException("Expected a PoseStack pose");
	}

	public static void translate(Object pose, float x, float y) {
		if (pose instanceof PoseStack poseStack) {
			poseStack.translate(x, y, 0.0f);
			return;
		}
		throw new IllegalStateException("Expected a PoseStack pose");
	}

	public static void scale(Object pose, float x, float y) {
		if (pose instanceof PoseStack poseStack) {
			poseStack.scale(x, y, 1.0f);
			return;
		}
		throw new IllegalStateException("Expected a PoseStack pose");
	}
}
