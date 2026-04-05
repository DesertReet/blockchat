package desertreet.blockchat.skin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.compat.BlockChatImageCompat;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatLogPrivacy;
import desertreet.blockchat.util.BlockChatPlayerUuid;
import desertreet.blockchat.util.BlockChatPaths;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads Minecraft skins, caches them locally under gameDir/.blockchat/skins,
 * and extracts face pixels (8-15 x, 8-15 y) for use in UI rendering.
 * Skins are refreshed at most once per week.
 */
public final class SkinHelper {

	private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 1 week
	private static final int FACE_X = 8;
	private static final int FACE_Y = 8;
	private static final int FACE_SIZE = 8;
	// Hat overlay layer on the skin
	private static final int HAT_X = 40;
	private static final int HAT_Y = 8;

	private static final Map<String, ResourceLocation> faceTextures = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> pendingDownloads = new ConcurrentHashMap<>();

	private SkinHelper() {}

	private static boolean looksLikeMinecraftUuid(String dashlessLower) {
		if (dashlessLower == null || dashlessLower.length() != 32) {
			return false;
		}
		for (int i = 0; i < 32; i++) {
			char c = dashlessLower.charAt(i);
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the cached skins directory path: {gameDir}/.blockchat/skins/
	 */
	private static Path getSkinsDir() {
		return BlockChatPaths.getRuntimePath("skins");
	}

	/**
	 * Returns the cached skin file path for a given UUID.
	 */
	private static Path getSkinPath(String uuid) {
		Path dir = getSkinsDir();
		if (dir == null) return null;
		return dir.resolve(uuid + ".png");
	}

	/**
	 * Returns the timestamp file path tracking when the skin was last downloaded.
	 */
	private static Path getTimestampPath(String uuid) {
		Path dir = getSkinsDir();
		if (dir == null) return null;
		return dir.resolve(uuid + ".ts");
	}

	/**
	 * Checks if the cached skin needs refreshing (older than 1 week or doesn't exist).
	 */
	private static boolean needsRefresh(String uuid) {
		Path skinPath = getSkinPath(uuid);
		Path tsPath = getTimestampPath(uuid);
		if (skinPath == null || !Files.exists(skinPath)) return true;
		if (tsPath == null || !Files.exists(tsPath)) return true;
		try {
			long lastDownload = Long.parseLong(Files.readString(tsPath).trim());
			return System.currentTimeMillis() - lastDownload > CACHE_MAX_AGE_MS;
		} catch (Exception e) {
			return true;
		}
	}

	/**
	 * Downloads a skin from the given URL and saves it to the cache.
	 * Returns true on success.
	 */
	private static boolean downloadSkin(String skinUrl, String uuid) {
		try {
			Path skinsDir = getSkinsDir();
			if (skinsDir == null) return false;
			BlockChatPaths.ensureRuntimeDirectory(skinsDir);

			HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(skinUrl))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
			HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() != 200) {
				BlockChatClientMod.LOGGER.warn("SkinHelper: failed to download skin, status {}", response.statusCode());
				BlockChatDebugLog.line(
					"skin",
					"download_http_error status=" + response.statusCode()
						+ " url=" + BlockChatLogPrivacy.privacySafeUrlHost(skinUrl)
				);
				return false;
			}

			Path skinPath = getSkinPath(uuid);
			Path tsPath = getTimestampPath(uuid);
			if (skinPath == null || tsPath == null) return false;

			Files.write(skinPath, response.body());
			Files.writeString(tsPath, String.valueOf(System.currentTimeMillis()));
			return true;
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("SkinHelper: failed to download skin from {}", skinUrl, e);
			BlockChatDebugLog.line(
				"skin",
				"download_failed url=" + BlockChatLogPrivacy.privacySafeUrlHost(skinUrl),
				e
			);
			return false;
		}
	}

	/**
	 * Extracts the 8x8 face pixels from a full skin image.
	 * The face is at pixels 8-15 x, 8-15 y. The hat overlay at 40-47 x, 8-15 y
	 * is composited on top if it has any non-transparent pixels.
	 *
	 * @param skinImage the full skin NativeImage
	 * @return an 8x8 NativeImage of the face, or null on error
	 */
	public static NativeImage extractFacePixels(NativeImage skinImage) {
		if (skinImage == null) return null;
		if (skinImage.getWidth() < FACE_X + FACE_SIZE || skinImage.getHeight() < FACE_Y + FACE_SIZE) {
			return null;
		}

		NativeImage face = new NativeImage(NativeImage.Format.RGBA, FACE_SIZE, FACE_SIZE, false);

		// Copy base face layer
		for (int x = 0; x < FACE_SIZE; x++) {
			for (int y = 0; y < FACE_SIZE; y++) {
				BlockChatImageCompat.setPixel(face, x, y, BlockChatImageCompat.getPixel(skinImage, FACE_X + x, FACE_Y + y));
			}
		}

		// Composite hat overlay layer on top (if skin is wide enough)
		if (skinImage.getWidth() >= HAT_X + FACE_SIZE) {
			for (int x = 0; x < FACE_SIZE; x++) {
				for (int y = 0; y < FACE_SIZE; y++) {
					int basePixel = BlockChatImageCompat.getPixel(face, x, y);
					int hatPixel = BlockChatImageCompat.getPixel(skinImage, HAT_X + x, HAT_Y + y);
					BlockChatImageCompat.setPixel(face, x, y, alphaCompositeAbgr(basePixel, hatPixel));
				}
			}
		}

		return face;
	}

	private static int alphaCompositeAbgr(int basePixel, int overlayPixel) {
		int overlayAlpha = (overlayPixel >>> 24) & 0xFF;
		if (overlayAlpha <= 0) {
			return basePixel;
		}
		if (overlayAlpha >= 255) {
			return overlayPixel;
		}

		int baseAlpha = (basePixel >>> 24) & 0xFF;
		double srcAlpha = overlayAlpha / 255.0;
		double dstAlpha = baseAlpha / 255.0;
		double outAlpha = srcAlpha + dstAlpha * (1.0 - srcAlpha);
		if (outAlpha <= 0.0) {
			return 0;
		}

		int baseR = basePixel & 0xFF;
		int baseG = (basePixel >>> 8) & 0xFF;
		int baseB = (basePixel >>> 16) & 0xFF;
		int overlayR = overlayPixel & 0xFF;
		int overlayG = (overlayPixel >>> 8) & 0xFF;
		int overlayB = (overlayPixel >>> 16) & 0xFF;

		int outR = blendChannel(baseR, overlayR, outAlpha, srcAlpha, dstAlpha);
		int outG = blendChannel(baseG, overlayG, outAlpha, srcAlpha, dstAlpha);
		int outB = blendChannel(baseB, overlayB, outAlpha, srcAlpha, dstAlpha);
		int outA = clampChannel(outAlpha * 255.0);
		return (outA << 24) | (outB << 16) | (outG << 8) | outR;
	}

	private static int blendChannel(
		int baseChannel,
		int overlayChannel,
		double outAlpha,
		double srcAlpha,
		double dstAlpha
	) {
		double blended = ((overlayChannel * srcAlpha) + (baseChannel * dstAlpha * (1.0 - srcAlpha))) / outAlpha;
		return clampChannel(blended);
	}

	private static int clampChannel(double value) {
		return Math.max(0, Math.min(255, (int) Math.round(value)));
	}

	/**
	 * Ensures the skin for the given UUID/URL is downloaded and cached,
	 * then creates a face texture and returns its ResourceLocation for rendering.
	 * Downloads happen asynchronously when {@code skinUrl} is present; without a URL,
	 * an existing {@code .blockchat/skins/&lt;uuid&gt;.png} file is still loaded synchronously
	 * so UI (e.g. Chat tab) can show faces immediately when the server omits {@code skin_url}.
	 *
	 * @param skinUrl the URL to download the skin from (may be null if disk cache exists)
	 * @param uuid    the player UUID used as cache key
	 * @return the texture ResourceLocation for the face, or null if not yet ready
	 */
	public static ResourceLocation getFaceTexture(String skinUrl, String uuid) {
		String normalizedUuid = BlockChatPlayerUuid.canonicalize(uuid);
		if (normalizedUuid == null || normalizedUuid.isEmpty() || !looksLikeMinecraftUuid(normalizedUuid)) {
			return null;
		}
		boolean hasUrl = skinUrl != null && !skinUrl.isBlank();

		ResourceLocation existing = faceTextures.get(normalizedUuid);
		if (existing != null) {
			if (hasUrl && needsRefresh(normalizedUuid) && !pendingDownloads.containsKey(normalizedUuid)) {
				pendingDownloads.put(normalizedUuid, true);
				Thread.startVirtualThread(() -> refreshAndReload(skinUrl, normalizedUuid));
			}
			return existing;
		}

		Path skinPath = getSkinPath(normalizedUuid);
		boolean onDisk = skinPath != null && Files.exists(skinPath);

		if (onDisk && !needsRefresh(normalizedUuid)) {
			return loadFaceTexture(normalizedUuid);
		}

		if (hasUrl && !pendingDownloads.containsKey(normalizedUuid)) {
			pendingDownloads.put(normalizedUuid, true);
			Thread.startVirtualThread(() -> {
				downloadSkin(skinUrl, normalizedUuid);
				Minecraft.getInstance().execute(() -> {
					loadFaceTexture(normalizedUuid);
					pendingDownloads.remove(normalizedUuid);
				});
			});
		}

		if (onDisk) {
			return loadFaceTexture(normalizedUuid);
		}

		return null;
	}

	private static void refreshAndReload(String skinUrl, String uuid) {
		downloadSkin(skinUrl, uuid);
		Minecraft.getInstance().execute(() -> {
			// Unregister old texture
			ResourceLocation old = faceTextures.remove(uuid);
			if (old != null) {
				Minecraft.getInstance().getTextureManager().release(old);
			}
			loadFaceTexture(uuid);
			pendingDownloads.remove(uuid);
		});
	}

	/**
	 * Loads a cached skin file, extracts the face, and registers it as a dynamic texture.
	 * Returns the texture ResourceLocation, or null on failure.
	 */
	private static ResourceLocation loadFaceTexture(String uuid) {
		ResourceLocation existing = faceTextures.get(uuid);
		if (existing != null) return existing;

		Path skinPath = getSkinPath(uuid);
		if (skinPath == null || !Files.exists(skinPath)) return null;

		try (InputStream is = Files.newInputStream(skinPath)) {
			NativeImage fullSkin = NativeImage.read(is);
			NativeImage face = extractFacePixels(fullSkin);
			fullSkin.close();
			if (face == null) return null;

			DynamicTexture dynamicTexture = BlockChatImageCompat.createDynamicTexture("blockchat_face_" + uuid, face);
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("blockchat", "skin_face_" + uuid);
			Minecraft.getInstance().getTextureManager().register(id, dynamicTexture);
			faceTextures.put(uuid, id);
			return id;
		} catch (Exception e) {
			BlockChatClientMod.LOGGER.warn("SkinHelper: failed to load face texture for {}", uuid, e);
			return null;
		}
	}

	/**
	 * Cleans up all registered face textures. Call on mod shutdown or when no longer needed.
	 */
	public static void cleanup() {
		for (ResourceLocation id : faceTextures.values()) {
			Minecraft.getInstance().getTextureManager().release(id);
		}
		faceTextures.clear();
		pendingDownloads.clear();
	}

}
