package desertreet.blockchat.auth;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.config.BlockChatConfig;
import desertreet.blockchat.preferences.BlockChatPreferenceState;
import desertreet.blockchat.util.BlockChatDebugLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class BlockChatAuth {

	// Change this to your local development url when testing
	public static final String API_BASE_PROD = "https://blockchat.desertreet.com";

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private static final Gson GSON = new Gson();
	private static final int MAX_ERROR_MESSAGE_LEN = 220;
	private static final int MAX_LOG_BODY_LEN = 512;

	private BlockChatAuth() {
	}

	public static String getApiBase() {
		return API_BASE_PROD;
	}

	public static boolean isLoggedIn() {
		return BlockChatConfig.isLoggedIn();
	}

	/**
	 * Returns the local player's Minecraft username, or null if not available.
	 */
	public static String getLocalPlayerName() {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			return client.player.getGameProfile().name();
		}
		// Fallback to session user name when player entity isn't available
		return client.getUser().getName();
	}

	public static CompletableFuture<DeviceCodeResponse> requestDeviceCode() {
		String apiBase = getApiBase();
		BlockChatClientMod.LOGGER.info("Requesting device code from {}", apiBase + "/api/auth/device-code");
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(apiBase + "/api/auth/device-code"))
			.POST(HttpRequest.BodyPublishers.noBody())
			.timeout(Duration.ofSeconds(15))
			.build();

		return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				if (response.statusCode() != 200) {
					ParsedErrorBody err = parseErrorBody(response.body());
					BlockChatClientMod.LOGGER.warn(
						"Device code request failed: status={} error={} message={}",
						response.statusCode(),
						err.error(),
						err.message()
					);
					BlockChatDebugLog.line(
						"auth",
						"device_code_http_error status=" + response.statusCode()
							+ " error=" + err.error()
							+ " message=" + abbreviateForLog(err.message())
					);
					String message = userFacingMessage(err, BlockChatStrings.UNABLE_TO_START_SIGN_IN);
					throw new AuthException(BlockChatStrings.deviceCodeRequestFailed(message));
				}
				try {
					DeviceCodeResponse parsed = GSON.fromJson(response.body(), DeviceCodeResponse.class);
					if (parsed == null || isBlank(parsed.device_code) || isBlank(parsed.user_code) || isBlank(parsed.verification_uri)) {
						BlockChatClientMod.LOGGER.warn("Device code response missing required fields: status=200");
						BlockChatDebugLog.line("auth", "device_code_invalid_response");
						throw new AuthException(BlockChatStrings.INVALID_SIGN_IN_RESPONSE);
					}
					return parsed;
				} catch (AuthException ex) {
					throw ex;
				} catch (RuntimeException ex) {
					BlockChatClientMod.LOGGER.warn("Failed to parse device code response body", ex);
					BlockChatDebugLog.line("auth", "device_code_parse_failed", ex);
					throw new AuthException(BlockChatStrings.INVALID_SIGN_IN_RESPONSE);
				}
			});
	}

	/**
	 * Verify in normal mode: sends device_code + username.
	 */
	public static CompletableFuture<VerifyResponse> verify(String deviceCode) {
		String apiBase = getApiBase();
		JsonObject body = new JsonObject();
		body.addProperty("device_code", deviceCode);
		String playerName = getLocalPlayerName();
		if (playerName != null && !playerName.isEmpty()) {
			body.addProperty("username", playerName);
		}

		return sendVerifyRequest(apiBase, body);
	}

	private static CompletableFuture<VerifyResponse> sendVerifyRequest(String apiBase, JsonObject body) {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(apiBase + "/api/auth/verify"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
			.timeout(Duration.ofSeconds(30))
			.build();

		return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				if (response.statusCode() == 200) {
					try {
						VerifyResponse result = GSON.fromJson(response.body(), VerifyResponse.class);
						if (result == null || isBlank(result.session_token) || isBlank(result.uuid) || isBlank(result.username)) {
							BlockChatClientMod.LOGGER.warn(
								"Verify response missing required fields: status=200 body={}",
								abbreviateForLog(response.body())
							);
							BlockChatDebugLog.line("auth", "verify_invalid_response body=" + abbreviateForLog(response.body()));
							throw new AuthException(BlockChatStrings.INVALID_SIGN_IN_RESPONSE);
						}
						BlockChatConfig.setSessionToken(result.session_token);
						BlockChatConfig.setUserUuid(result.uuid);
						BlockChatConfig.setUsername(result.username);
						BlockChatConfig.setSkinUrl(result.skin_url);
						BlockChatConfig.save();
						BlockChatClientMod.LOGGER.info("BlockChat login successful for {}", result.username);
						return result;
					} catch (AuthException ex) {
						throw ex;
					} catch (RuntimeException ex) {
						BlockChatClientMod.LOGGER.warn(
							"Failed to parse verify response body: {}",
							abbreviateForLog(response.body()),
							ex
						);
						BlockChatDebugLog.line("auth", "verify_parse_failed body=" + abbreviateForLog(response.body()), ex);
						throw new AuthException(BlockChatStrings.INVALID_SIGN_IN_RESPONSE);
					}
				}

				ParsedErrorBody err = parseErrorBody(response.body());
				String error = err.error();
				String message = err.message();
				BlockChatClientMod.LOGGER.warn(
					"Verify request failed: status={} error={} message={} body={}",
					response.statusCode(),
					error,
					message,
					abbreviateForLog(response.body())
				);
				BlockChatDebugLog.line(
					"auth",
					"verify_http_error status=" + response.statusCode()
						+ " error=" + error
						+ " message=" + abbreviateForLog(message)
				);

				if (response.statusCode() == 400 && "authorization_pending".equals(error)) {
					throw new AuthException(BlockChatStrings.AUTHORIZATION_PENDING);
				} else if (response.statusCode() == 410 && "expired_token".equals(error)) {
					throw new AuthException(BlockChatStrings.LOGIN_CODE_EXPIRED);
				} else if (response.statusCode() == 403 && "username_mismatch".equals(error)) {
					throw new AuthException(BlockChatStrings.USERNAME_MISMATCH);
				} else {
					throw new AuthException(BlockChatStrings.verificationFailed(userFacingMessage(err, BlockChatStrings.PLEASE_TRY_AGAIN)));
				}
			});
	}

	private static ParsedErrorBody parseErrorBody(String rawBody) {
		String body = rawBody == null ? "" : rawBody.trim();
		if (body.isEmpty()) {
			return new ParsedErrorBody("unknown", "");
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (parsed.isJsonObject()) {
				JsonObject object = parsed.getAsJsonObject();
				return new ParsedErrorBody(
					readJsonString(object, "error", "unknown"),
					readJsonString(object, "message", "")
				);
			}
			if (parsed.isJsonPrimitive()) {
				String primitive = parsed.getAsJsonPrimitive().isString()
					? parsed.getAsString()
					: parsed.getAsJsonPrimitive().toString();
				return new ParsedErrorBody("unknown", primitive);
			}
			return new ParsedErrorBody("unknown", body);
		} catch (RuntimeException ignored) {
			return new ParsedErrorBody("unknown", body);
		}
	}

	private static String readJsonString(JsonObject object, String key, String fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			String value = object.get(key).getAsString();
			return value == null ? fallback : value;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static String userFacingMessage(ParsedErrorBody errorBody, String fallback) {
		String candidate = firstNonBlank(errorBody.message(), errorBody.error(), fallback);
		if (candidate == null) {
			return fallback;
		}
		String compact = candidate.replaceAll("\\s+", " ").trim();
		if (compact.isEmpty()) {
			return fallback;
		}
		if (looksLikeParserInternal(compact)) {
			return fallback;
		}
		if (compact.length() > MAX_ERROR_MESSAGE_LEN) {
			return compact.substring(0, MAX_ERROR_MESSAGE_LEN - 3) + "...";
		}
		return compact;
	}

	private static boolean looksLikeParserInternal(String message) {
		String lower = message.toLowerCase();
		return lower.contains("com.google.gson")
			|| lower.contains("jsonprimitive")
			|| lower.contains("jsonobject")
			|| (lower.contains("expected") && lower.contains(" at path $"));
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String abbreviateForLog(String body) {
		if (body == null) {
			return "<null>";
		}
		String compact = body.replaceAll("\\s+", " ").trim();
		if (compact.length() <= MAX_LOG_BODY_LEN) {
			return compact;
		}
		return compact.substring(0, MAX_LOG_BODY_LEN - 3) + "...";
	}

	private record ParsedErrorBody(String error, String message) {
	}

	public static void logout() {
		BlockChatConfig.clearSession();
		BlockChatPreferenceState.clear();
		BlockChatClientMod.LOGGER.info("BlockChat session cleared");
		BlockChatDebugLog.line("auth", "session_cleared reason=user_sign_out");
	}

	/**
	 * Session invalidated by the server or WebSocket auth failure — not an explicit user sign-out.
	 */
	public static void logoutDueToAuthFailure(String reason) {
		BlockChatConfig.clearSession();
		BlockChatPreferenceState.clear();
		BlockChatClientMod.LOGGER.info("BlockChat session cleared");
		String r = reason == null ? "" : reason.replace('\n', ' ').trim();
		if (r.length() > 220) {
			r = r.substring(0, 217) + "...";
		}
		BlockChatDebugLog.line(
			"auth",
			"session_cleared reason=forced_sign_out detail=" + (r.isEmpty() ? "(none)" : r)
		);
	}

	public static class DeviceCodeResponse {
		public String user_code;
		public String verification_uri;
		public String device_code;
		public int expires_in;
		public int interval;
		public String message;
	}

	public static class VerifyResponse {
		public String uuid;
		public String username;
		public String skin_url;
		public String session_token;
	}

	public static class AuthException extends RuntimeException {
		public AuthException(String message) {
			super(message);
		}
	}
}
