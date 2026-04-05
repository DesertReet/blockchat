package desertreet.blockchat.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.compat.BlockChatImageCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Minecraft 1.21.2-1.21.4 still use the older synchronous screenshot copy path,
 * but expose the newer NativeImage pixel accessors.
 */
public final class FrameGrabber {

	private static final long TARGET_FRAME_INTERVAL_NS = BlockChatVideoConstants.FRAME_INTERVAL_NS;

	private int width;
	private int height;
	private int frameByteSize;
	private boolean initialized;
	private long nextCaptureNanos;

	@FunctionalInterface
	public interface FrameConsumer {
		void accept(byte[] rgbaData, long captureTimeNanos);
	}

	public void init(int width, int height) {
		this.width = width;
		this.height = height;
		this.frameByteSize = width * height * 4;
		this.initialized = true;
		this.nextCaptureNanos = 0L;
		BlockChatClientMod.LOGGER.debug("FrameGrabber initialized: {}x{}, {} bytes/frame (1.21.2 screenshot fallback)", width, height, frameByteSize);
	}

	public void grabFrame(FrameConsumer onFrameReady) {
		if (!initialized || onFrameReady == null) {
			return;
		}

		long now = System.nanoTime();
		if (nextCaptureNanos == 0L) {
			nextCaptureNanos = now;
		}
		if (now < nextCaptureNanos) {
			CaptureDiagnostics.recordGrabRateLimited();
			return;
		}
		do {
			nextCaptureNanos += TARGET_FRAME_INTERVAL_NS;
		} while (nextCaptureNanos <= now);

		Minecraft client = Minecraft.getInstance();
		RenderTarget target = client.getMainRenderTarget();
		if (target.width != width || target.height != height) {
			return;
		}

		CaptureDiagnostics.recordGrabQueued();
		try (NativeImage image = Screenshot.takeScreenshot(target)) {
			byte[] rgba = new byte[frameByteSize];
			int out = 0;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int pixel = BlockChatImageCompat.getPixel(image, x, y);
					rgba[out++] = (byte) (pixel & 0xFF);
					rgba[out++] = (byte) ((pixel >>> 8) & 0xFF);
					rgba[out++] = (byte) ((pixel >>> 16) & 0xFF);
					rgba[out++] = (byte) ((pixel >>> 24) & 0xFF);
				}
			}
			CaptureDiagnostics.recordFrameReady();
			onFrameReady.accept(rgba, now);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("BlockChat could not capture a frame on Minecraft 1.21.2-1.21.4", e);
		}
	}

	public void cleanup() {
		initialized = false;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}
}
