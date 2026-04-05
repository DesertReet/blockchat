package desertreet.blockchat.compat;

public final class BlockChatLegacyInputs {

	private BlockChatLegacyInputs() {
	}

	public record MouseButton(double x, double y, int button, int modifiers) {
	}

	public record Key(int key, int scancode, int modifiers) {
	}

	public record Character(int codepoint, int modifiers) {
		public char legacyCharacter() {
			return (char) codepoint;
		}
	}
}
