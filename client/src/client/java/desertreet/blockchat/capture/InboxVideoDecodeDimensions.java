package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatClientMod;

/**
 * Inbox snap playback decodes video at a fixed 1080px frame height; width follows the
 * source aspect ratio from the snap metadata, with a conservative fallback if metadata is missing.
 */
public final class InboxVideoDecodeDimensions {

	public static final int INBOX_DECODE_HEIGHT = 1080;

	private static final int MIN_DECODE_WIDTH = 16;
	/** Avoid huge RGBA buffers if metadata is bogus. */
	private static final int MAX_DECODE_WIDTH = 8192;

	private static final int FALLBACK_DECODE_WIDTH = 1920;
	private static final int FALLBACK_DECODE_HEIGHT = INBOX_DECODE_HEIGHT;

	private InboxVideoDecodeDimensions() {
	}

	/**
	 * @return {@code [width, height]} with height {@link #INBOX_DECODE_HEIGHT}
	 */
	public static int[] resolve(int mediaWidth, int mediaHeight) {
		int[] fromMeta = fromPixelDimensions(mediaWidth, mediaHeight);
		if (fromMeta != null) {
			return fromMeta;
		}
		BlockChatClientMod.LOGGER.warn(
			"BlockChat inbox: could not resolve video dimensions; using {}x{} decode",
			FALLBACK_DECODE_WIDTH,
			FALLBACK_DECODE_HEIGHT
		);
		return new int[]{FALLBACK_DECODE_WIDTH, FALLBACK_DECODE_HEIGHT};
	}

	private static int[] fromPixelDimensions(int width, int height) {
		if (width <= 0 || height <= 0) {
			return null;
		}
		double aspect = (double) width / (double) height;
		int w = (int) Math.round(INBOX_DECODE_HEIGHT * aspect);
		w = Math.max(MIN_DECODE_WIDTH, Math.min(MAX_DECODE_WIDTH, w));
		if (w % 2 != 0) {
			w--;
		}
		if (w < MIN_DECODE_WIDTH) {
			w = MIN_DECODE_WIDTH;
		}
		return new int[]{w, INBOX_DECODE_HEIGHT};
	}
}
