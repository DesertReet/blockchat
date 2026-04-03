package desertreet.blockchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatPaths;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores BlockChat credentials in a locally encrypted file within the game directory.
 * Each Minecraft/Prism instance gets its own isolated secrets file under gameDir/.blockchat.
 * <p>
 * Encryption: AES-256-GCM with a key derived via PBKDF2 from the game directory's absolute path.
 * A random salt is generated once and stored alongside the encrypted data.
 */
public final class EncryptedTokenStore {

	// Well-known keys
	public static final String KEY_SESSION_TOKEN = "session-token";
	public static final String KEY_USER_UUID = "user-uuid";
	public static final String KEY_USERNAME = "username";
	public static final String KEY_SKIN_URL = "skin-url";

	private static final String SECRETS_FILE = ".secrets.enc";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

	private static final int SALT_LENGTH = 16;
	private static final int IV_LENGTH = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final int KEY_LENGTH = 256;
	private static final int PBKDF2_ITERATIONS = 100_000;

	private EncryptedTokenStore() {}

	/**
	 * Store a value under the given key. No-op if value is null or empty.
	 */
	public static void store(String key, String value) {
		if (value == null || value.isEmpty()) {
			return;
		}
		try {
			Map<String, String> secrets = loadSecrets();
			secrets.put(key, value);
			saveSecrets(secrets);
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("EncryptedTokenStore: failed to store {}", key, e);
			BlockChatDebugLog.line("storage", "encrypted_store_failed op=store key=" + key, e);
		}
	}

	/**
	 * Retrieve a value. Returns empty string on failure or if not found.
	 */
	public static String retrieve(String key) {
		try {
			Map<String, String> secrets = loadSecrets();
			String value = secrets.get(key);
			return value != null ? value : "";
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("EncryptedTokenStore: failed to retrieve {}", key, e);
			BlockChatDebugLog.line("storage", "encrypted_store_failed op=retrieve key=" + key, e);
			return "";
		}
	}

	/**
	 * Delete a value.
	 */
	public static void delete(String key) {
		try {
			Map<String, String> secrets = loadSecrets();
			if (secrets.remove(key) != null) {
				saveSecrets(secrets);
			}
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("EncryptedTokenStore: failed to delete {}", key, e);
			BlockChatDebugLog.line("storage", "encrypted_store_failed op=delete key=" + key, e);
		}
	}

	/**
	 * Delete all BlockChat keys.
	 */
	public static void deleteAll() {
		try {
			Path file = getSecretsPath();
			if (file != null && Files.exists(file)) {
				Files.delete(file);
			}
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("EncryptedTokenStore: failed to delete all", e);
			BlockChatDebugLog.line("storage", "encrypted_store_failed op=delete_all", e);
		}
	}

	// ── Encryption ────────────────────────────────────────────────────────

	private static SecretKey deriveKey(byte[] salt) throws Exception {
		Path gameDir = BlockChatPaths.getGameDirectoryPath();
		String passphrase = gameDir != null ? gameDir.toAbsolutePath().toString() : "blockchat-default";

		KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		byte[] keyBytes = factory.generateSecret(spec).getEncoded();
		return new SecretKeySpec(keyBytes, "AES");
	}

	private static byte[] encrypt(String plaintext, byte[] salt) throws Exception {
		SecretKey key = deriveKey(salt);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

		byte[] iv = new byte[IV_LENGTH];
		new SecureRandom().nextBytes(iv);

		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

		// Prepend IV to ciphertext
		byte[] result = new byte[IV_LENGTH + ciphertext.length];
		System.arraycopy(iv, 0, result, 0, IV_LENGTH);
		System.arraycopy(ciphertext, 0, result, IV_LENGTH, ciphertext.length);
		return result;
	}

	private static String decrypt(byte[] data, byte[] salt) throws Exception {
		SecretKey key = deriveKey(salt);

		byte[] iv = new byte[IV_LENGTH];
		System.arraycopy(data, 0, iv, 0, IV_LENGTH);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

		byte[] plaintext = cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
		return new String(plaintext, StandardCharsets.UTF_8);
	}

	// ── File I/O ──────────────────────────────────────────────────────────

	private static Map<String, String> loadSecrets() throws Exception {
		Path file = getSecretsPath();
		if (file == null || !Files.exists(file)) {
			return new HashMap<>();
		}

		byte[] raw = Files.readAllBytes(file);
		if (raw.length < SALT_LENGTH + IV_LENGTH + 1) {
			return new HashMap<>();
		}

		byte[] salt = new byte[SALT_LENGTH];
		System.arraycopy(raw, 0, salt, 0, SALT_LENGTH);

		byte[] encrypted = new byte[raw.length - SALT_LENGTH];
		System.arraycopy(raw, SALT_LENGTH, encrypted, 0, encrypted.length);

		String json = decrypt(encrypted, salt);
		Map<String, String> map = GSON.fromJson(json, MAP_TYPE);
		return map != null ? new HashMap<>(map) : new HashMap<>();
	}

	private static void saveSecrets(Map<String, String> secrets) throws Exception {
		Path file = getSecretsPath();
		if (file == null) {
			return;
		}

		BlockChatPaths.ensureRuntimeDirectory(file.getParent());

		String json = GSON.toJson(secrets);

		// Read existing salt or generate new one
		byte[] salt;
		if (Files.exists(file)) {
			byte[] existing = Files.readAllBytes(file);
			if (existing.length >= SALT_LENGTH) {
				salt = new byte[SALT_LENGTH];
				System.arraycopy(existing, 0, salt, 0, SALT_LENGTH);
			} else {
				salt = new byte[SALT_LENGTH];
				new SecureRandom().nextBytes(salt);
			}
		} else {
			salt = new byte[SALT_LENGTH];
			new SecureRandom().nextBytes(salt);
		}

		byte[] encrypted = encrypt(json, salt);

		// Prepend salt to encrypted data
		byte[] output = new byte[SALT_LENGTH + encrypted.length];
		System.arraycopy(salt, 0, output, 0, SALT_LENGTH);
		System.arraycopy(encrypted, 0, output, SALT_LENGTH, encrypted.length);

		boolean isNew = !Files.exists(file);
		Files.write(file, output);

		// Set hidden attribute on Windows for newly created files
		if (isNew) {
			DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
			if (dos != null) {
				dos.setHidden(true);
			}
		}
	}

	// ── Paths ─────────────────────────────────────────────────────────────

	private static Path getSecretsPath() {
		return BlockChatPaths.getRuntimePath(SECRETS_FILE);
	}
}
