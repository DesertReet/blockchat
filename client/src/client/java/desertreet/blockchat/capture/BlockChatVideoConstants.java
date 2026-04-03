package desertreet.blockchat.capture;

/**
 * Recording and in-UI playback both target this framerate; encode/decode FFmpeg
 * {@code -r} and frame pump timing use the same value.
 */
public final class BlockChatVideoConstants {

	public static final int CAPTURE_FPS = 60;
	public static final long FRAME_INTERVAL_NS = 1_000_000_000L / CAPTURE_FPS;

	private BlockChatVideoConstants() {
	}
}
