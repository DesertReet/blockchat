package desertreet.blockchat.hud;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic identifiers so each BlockChat toast instance gets a distinct {@link net.minecraft.client.gui.components.toasts.Toast#getToken()}
 * value; vanilla merges/replaces toasts when class + token match.
 */
public final class BlockChatToastTokens {
	private static final AtomicLong MONOTONIC = new AtomicLong();

	private BlockChatToastTokens() {
	}

	/** Returns a new positive id; never reuse, so {@code ToastManager} will not merge separate BlockChat notifications. */
	public static long nextMonotonicToken() {
		return MONOTONIC.incrementAndGet();
	}
}
