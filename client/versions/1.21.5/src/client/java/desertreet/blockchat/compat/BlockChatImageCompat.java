package desertreet.blockchat.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.function.Supplier;

public final class BlockChatImageCompat {

	private BlockChatImageCompat() {
	}

	public static DynamicTexture createDynamicTexture(String debugName, NativeImage image) {
		Supplier<String> debugNameSupplier = () -> debugName;
		return new DynamicTexture(debugNameSupplier, image);
	}

	public static DynamicTexture createDynamicTexture(String debugName, int width, int height, boolean useStb) {
		Supplier<String> debugNameSupplier = () -> debugName;
		return new DynamicTexture(debugNameSupplier, width, height, useStb);
	}

	public static int getPixel(NativeImage image, int x, int y) {
		return image.getPixel(x, y);
	}

	public static void setPixel(NativeImage image, int x, int y, int abgr) {
		image.setPixelABGR(x, y, abgr);
	}
}
