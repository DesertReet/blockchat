package desertreet.blockchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatPlayerUuid;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BlockChatConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static int lastTabIndex = 0;
	private static boolean micEnabled = true;
	private static boolean recordingDiagnosticsEnabled = false;
	private static String preferredMicDeviceId = "";
	private static String preferredMicDeviceName = "";
	private static String sessionToken = "";
	private static String userUuid = "";
	private static String username = "";
	private static String skinUrl = "";

	private BlockChatConfig() {
	}

	public static void load() {
		Path configPath = getConfigPath();
		if (configPath != null && Files.exists(configPath)) {
			try {
				String json = Files.readString(configPath);
				ConfigData data = GSON.fromJson(json, ConfigData.class);
				if (data != null) {
					lastTabIndex = data.lastTabIndex;
					if (data.micEnabled != null) {
						micEnabled = data.micEnabled;
					}
					if (data.recordingDiagnosticsEnabled != null) {
						recordingDiagnosticsEnabled = data.recordingDiagnosticsEnabled;
					}
					preferredMicDeviceId = data.preferredMicDeviceId != null ? data.preferredMicDeviceId : "";
					preferredMicDeviceName = data.preferredMicDeviceName != null ? data.preferredMicDeviceName : "";
				}
			} catch (Exception e) {
				BlockChatClientMod.LOGGER.warn("Failed to load BlockChat config", e);
			}
		}

		// Load profile data from BlockChat's local encrypted token store.
		loadProfileFromEncryptedStore();
	}

	public static void save() {
		Path configPath = getConfigPath();
		if (configPath == null) {
			return;
		}
		try {
			Files.createDirectories(configPath.getParent());
			ConfigData data = new ConfigData();
			data.lastTabIndex = lastTabIndex;
			data.micEnabled = micEnabled;
			data.recordingDiagnosticsEnabled = recordingDiagnosticsEnabled;
			data.preferredMicDeviceId = preferredMicDeviceId;
			data.preferredMicDeviceName = preferredMicDeviceName;
			// userUuid, username, skinUrl, and sessionToken live in the encrypted token store, not this JSON file
			Files.writeString(configPath, GSON.toJson(data));
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("Failed to save BlockChat config", e);
		}
	}

	public static int getLastTabIndex() {
		return lastTabIndex;
	}

	public static void setLastTabIndex(int index) {
		lastTabIndex = index;
	}

	public static boolean isMicEnabled() {
		return micEnabled;
	}

	public static void setMicEnabled(boolean enabled) {
		micEnabled = enabled;
	}

	public static boolean isRecordingDiagnosticsEnabled() {
		return recordingDiagnosticsEnabled;
	}

	public static void setRecordingDiagnosticsEnabled(boolean enabled) {
		recordingDiagnosticsEnabled = enabled;
	}

	public static String getPreferredMicDeviceId() {
		return preferredMicDeviceId;
	}

	public static void setPreferredMicDeviceId(String deviceId) {
		preferredMicDeviceId = deviceId != null ? deviceId : "";
	}

	public static String getPreferredMicDeviceName() {
		return preferredMicDeviceName;
	}

	public static void setPreferredMicDeviceName(String deviceName) {
		preferredMicDeviceName = deviceName != null ? deviceName : "";
	}

	public static String getSessionToken() {
		return sessionToken;
	}

	public static void setSessionToken(String token) {
		sessionToken = token != null ? token : "";
		if (!sessionToken.isEmpty()) {
			EncryptedTokenStore.store(EncryptedTokenStore.KEY_SESSION_TOKEN, sessionToken);
		} else {
			EncryptedTokenStore.delete(EncryptedTokenStore.KEY_SESSION_TOKEN);
		}
	}

	public static String getUserUuid() {
		return userUuid;
	}

	public static void setUserUuid(String uuid) {
		// BlockChat identity keys are always stored without dashes so every cache and WS path agrees.
		String c = BlockChatPlayerUuid.canonicalize(uuid);
		userUuid = c != null ? c : "";
		EncryptedTokenStore.store(EncryptedTokenStore.KEY_USER_UUID, userUuid);
	}

	public static String getUsername() {
		return username;
	}

	public static void setUsername(String name) {
		username = name != null ? name : "";
		EncryptedTokenStore.store(EncryptedTokenStore.KEY_USERNAME, username);
	}

	public static String getSkinUrl() {
		return skinUrl;
	}

	public static void setSkinUrl(String url) {
		skinUrl = url != null ? url : "";
		EncryptedTokenStore.store(EncryptedTokenStore.KEY_SKIN_URL, skinUrl);
	}

	public static boolean isLoggedIn() {
		return !sessionToken.isEmpty() && !userUuid.isEmpty();
	}

	public static void clearSession() {
		sessionToken = "";
		userUuid = "";
		username = "";
		skinUrl = "";
		EncryptedTokenStore.deleteAll();
		save();
	}

	private static void loadProfileFromEncryptedStore() {
		sessionToken = EncryptedTokenStore.retrieve(EncryptedTokenStore.KEY_SESSION_TOKEN);
		String c = BlockChatPlayerUuid.canonicalize(EncryptedTokenStore.retrieve(EncryptedTokenStore.KEY_USER_UUID));
		userUuid = c != null ? c : "";
		username = EncryptedTokenStore.retrieve(EncryptedTokenStore.KEY_USERNAME);
		skinUrl = EncryptedTokenStore.retrieve(EncryptedTokenStore.KEY_SKIN_URL);
	}

	private static Path getConfigPath() {
		return BlockChatPaths.getConfigPath();
	}

	private static class ConfigData {
		int lastTabIndex = 0;
		Boolean micEnabled = true;
		Boolean recordingDiagnosticsEnabled = false;
		String preferredMicDeviceId = "";
		String preferredMicDeviceName = "";
	}
}
