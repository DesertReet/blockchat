package desertreet.blockchat.capture;

import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously discovers microphone-capable input devices for the BlockChat settings UI.
 * This class does not open or record from any lines yet; it only exposes discovery state.
 */
public final class MicrophoneDeviceManager {

	private static volatile boolean detectionDone;
	private static volatile boolean detectionInProgress;
	private static volatile List<MicrophoneDevice> devices = List.of();

	private MicrophoneDeviceManager() {
	}

	public static void detectAsync() {
		if (detectionInProgress) {
			return;
		}
		detectionInProgress = true;
		detectionDone = false;
		CompletableFuture.runAsync(() -> {
			List<MicrophoneDevice> discovered = discoverDevices();
			devices = List.copyOf(discovered);
			detectionDone = true;
			detectionInProgress = false;
			BlockChatClientMod.LOGGER.info("Microphone discovery complete: {} device(s)", devices.size());
		});
	}

	public static boolean isDetectionDone() {
		return detectionDone;
	}

	public static boolean isDetectionInProgress() {
		return detectionInProgress;
	}

	public static List<MicrophoneDevice> getDevices() {
		return devices;
	}

	public static Optional<MicrophoneDevice> findPreferredDevice(String preferredId, String preferredName) {
		for (MicrophoneDevice device : devices) {
			if (!preferredId.isBlank() && device.id().equals(preferredId)) {
				return Optional.of(device);
			}
		}
		for (MicrophoneDevice device : devices) {
			if (!preferredName.isBlank() && device.displayName().equals(preferredName)) {
				return Optional.of(device);
			}
		}
		return Optional.empty();
	}

	private static List<MicrophoneDevice> discoverDevices() {
		List<MicrophoneDevice> discovered = new ArrayList<>();
		try {
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				try {
					Mixer mixer = AudioSystem.getMixer(info);
					if (!supportsTargetDataLine(mixer)) {
						continue;
					}
					discovered.add(new MicrophoneDevice(buildId(info), buildDisplayName(info)));
				} catch (Exception e) {
					BlockChatClientMod.LOGGER.debug("Skipping microphone mixer {}", info.getName(), e);
				}
			}
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("Failed to enumerate microphone devices", e);
		}
		discovered.sort(Comparator.comparing(device -> device.displayName().toLowerCase()));
		return discovered;
	}

	private static boolean supportsTargetDataLine(Mixer mixer) {
		for (Line.Info info : mixer.getTargetLineInfo()) {
			if (TargetDataLine.class.isAssignableFrom(info.getLineClass())) {
				return true;
			}
		}
		return false;
	}

	private static String buildId(Mixer.Info info) {
		return String.join(
			"|",
			nullToEmpty(info.getName()),
			nullToEmpty(info.getVendor()),
			nullToEmpty(info.getVersion()),
			nullToEmpty(info.getDescription())
		);
	}

	private static String buildDisplayName(Mixer.Info info) {
		String name = nullToEmpty(info.getName());
		String vendor = nullToEmpty(info.getVendor());
		if (!vendor.isBlank() && !name.equalsIgnoreCase(vendor)) {
			return BlockChatStrings.microphoneNameWithVendor(name, vendor);
		}
		return name.isBlank() ? BlockChatStrings.UNNAMED_MICROPHONE : name;
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	public record MicrophoneDevice(String id, String displayName) {
	}
}
