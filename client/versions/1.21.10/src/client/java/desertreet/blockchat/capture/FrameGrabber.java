package desertreet.blockchat.capture;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import desertreet.blockchat.BlockChatClientMod;

import java.nio.ByteBuffer;
/**
 * Async readback from the main {@link RenderTarget} color texture (same path as vanilla
 * screenshots). Double-buffered like PBO ping-pong: each completed copy returns the previous
 * frame's pixels while the GPU fills the other buffer.
 */
public final class FrameGrabber {

	private static final int BUFFER_COUNT = 2;
	private static final int BUFFER_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
	private static final long TARGET_FRAME_INTERVAL_NS = BlockChatVideoConstants.FRAME_INTERVAL_NS;

	private int width;
	private int height;
	private int frameByteSize;
	private final GpuBuffer[] buffers = new GpuBuffer[BUFFER_COUNT];
	private final long[] bufferCaptureNanos = new long[BUFFER_COUNT];
	private int writeIndex;
	private int readIndex = 1;
	private boolean firstFrame = true;
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
		closeBuffers();
		for (int i = 0; i < BUFFER_COUNT; i++) {
			final int index = i;
			this.buffers[i] = RenderSystem.getDevice().createBuffer(
				() -> "BlockChat capture " + index,
				BUFFER_USAGE,
				frameByteSize
			);
		}
		this.writeIndex = 0;
		this.readIndex = 1;
		this.firstFrame = true;
		this.nextCaptureNanos = 0;
		for (int i = 0; i < BUFFER_COUNT; i++) {
			this.bufferCaptureNanos[i] = 0L;
		}
		this.initialized = true;
		BlockChatClientMod.LOGGER.debug(
			"FrameGrabber initialized: {}x{}, {} bytes/frame (GpuBuffer)",
			width,
			height,
			frameByteSize
		);
	}

	/**
	 * Queues a copy from the main render target. Invokes {@code onFrameReady} with the
	 * previous completed frame (or not at all on the first scheduled capture), on the render
	 * thread when the fenced copy completes — call {@link RenderSystem#executePendingTasks()}
	 * later the same frame to drain promptly.
	 */
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

		GpuTexture color = target.getColorTexture();
		if (color == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		int w = writeIndex;
		int r = readIndex;
		GpuBuffer dst = buffers[w];
		bufferCaptureNanos[w] = now;
		CaptureDiagnostics.recordGrabQueued();
		encoder.copyTextureToBuffer(color, dst, 0, () -> {
			RenderSystem.assertOnRenderThread();
			if (!initialized) {
				return;
			}
			GpuBuffer readBuf = buffers[r];
			byte[] result = null;
			long captureTimeNanos = bufferCaptureNanos[r];
			if (!firstFrame) {
				if (readBuf == null || readBuf.isClosed()) {
					return;
				}
				try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readBuf, true, false)) {
					ByteBuffer data = mapped.data();
					result = new byte[frameByteSize];
					data.position(0);
					data.get(result);
				}
			} else {
				firstFrame = false;
			}
			int tmp = writeIndex;
			writeIndex = readIndex;
			readIndex = tmp;
			if (result != null) {
				CaptureDiagnostics.recordFrameReady();
				onFrameReady.accept(result, captureTimeNanos != 0L ? captureTimeNanos : now);
			}
		}, 0);
	}

	public void cleanup() {
		closeBuffers();
		initialized = false;
		BlockChatClientMod.LOGGER.debug("FrameGrabber cleaned up");
	}

	private void closeBuffers() {
		for (int i = 0; i < BUFFER_COUNT; i++) {
			if (buffers[i] != null) {
				buffers[i].close();
				buffers[i] = null;
			}
		}
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
