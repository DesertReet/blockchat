package desertreet.blockchat.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

public final class BlockChatImageCompat {

	private BlockChatImageCompat() {
	}

	public static DynamicTexture createDynamicTexture(String debugName, NativeImage image) {
		return new DynamicTexture(image);
	}

	public static DynamicTexture createDynamicTexture(String debugName, int width, int height, boolean useStb) {
		return new DynamicTexture(width, height, useStb);
	}

	public static int getPixel(NativeImage image, int x, int y) {
		return image.getPixel(x, y);
	}

	public static void setPixel(NativeImage image, int x, int y, int abgr) {
		image.setPixel(x, y, abgr);
	}
}
