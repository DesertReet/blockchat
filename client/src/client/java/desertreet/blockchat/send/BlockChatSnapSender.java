package desertreet.blockchat.send;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import desertreet.blockchat.BlockChatStrings;
import desertreet.blockchat.BlockChatClientMod;
import desertreet.blockchat.capture.PendingCapture;
import desertreet.blockchat.chat.BlockChatChatState;
import desertreet.blockchat.net.BlockChatWebSocket;
import desertreet.blockchat.util.BlockChatDebugLog;
import desertreet.blockchat.util.BlockChatLogPrivacy;
import desertreet.blockchat.util.BlockChatPlayerUuid;
import desertreet.blockchat.social.BlockChatSocialState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class BlockChatSnapSender {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	private static final AtomicReference<PendingSend> PENDING_SEND = new AtomicReference<>();
	private static final BlockChatWebSocket.MessageListener WEBSOCKET_LISTENER = new BlockChatWebSocket.MessageListener() {
		@Override
		public void onMessage(JsonObject message) {
			handleMessage(message);
		}

		@Override
		public void onConnected() {
		}

		@Override
		public void onDisconnected(String reason) {
			PendingSend pending = PENDING_SEND.getAndSet(null);
			if (pending != null) {
				BlockChatDebugLog.line(
					"send",
					"pending_send_cancelled reason=disconnect detail=" + (reason == null ? "" : reason.replace('\n', ' '))
				);
				deleteQuietly(pending.stagedMediaPath());
				Minecraft.getInstance().execute(() -> BlockChatChatState.rollbackOptimisticOutgoing(pending.optimisticSendId()));
			}
		}
	};

	private static boolean listenerInstalled;

	private BlockChatSnapSender() {
	}

	public static boolean startSend(
		PendingCapture.PendingMedia media,
		List<SendTarget> rawTargets,
		String captionText,
		double captionOffsetY,
		long expiryMs
	) {
		List<SendTarget> targets = dedupeTargets(rawTargets);
		if (targets.isEmpty()) {
			showClientMessage(BlockChatStrings.SELECT_AT_LEAST_ONE_RECIPIENT);
			return false;
		}
		if (targets.size() > 10) {
			showClientMessage(BlockChatStrings.SEND_LIMIT_EXCEEDED);
			return false;
		}
		if (!allTargetsAreFriends(targets)) {
			showClientMessage(BlockChatStrings.SEND_FRIENDS_ONLY);
			return false;
		}
		if (!BlockChatWebSocket.isConnected()) {
			showClientMessage(BlockChatStrings.NOT_CONNECTED_RIGHT_NOW);
			return false;
		}
		if (media == null || media.path() == null || !Files.isRegularFile(media.path())) {
			showClientMessage(BlockChatStrings.CAPTURE_NO_LONGER_AVAILABLE);
			return false;
		}
		if (PENDING_SEND.get() != null) {
			showClientMessage(BlockChatStrings.SEND_ALREADY_IN_PROGRESS);
			return false;
		}

		Path stagedMediaPath;
		try {
			stagedMediaPath = stageMediaFile(media);
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("Failed to stage BlockChat send media", e);
			BlockChatDebugLog.line("send", "stage_media_failed", e);
			showClientMessage(BlockChatStrings.FAILED_PREPARE_CAPTURE);
			return false;
		}

		ensureListener();
		String mediaType = media.type() == PendingCapture.MediaType.VIDEO ? "video" : "image";
		String contentType = detectContentType(media.type(), stagedMediaPath);
		int[] mediaDimensions;
		try {
			mediaDimensions = resolveMediaDimensions(media, stagedMediaPath);
		} catch (IOException e) {
			deleteQuietly(stagedMediaPath);
			BlockChatClientMod.LOGGER.warn("Failed to read BlockChat media dimensions", e);
			BlockChatDebugLog.line("send", "read_media_dimensions_failed", e);
			showClientMessage(BlockChatStrings.FAILED_READ_CAPTURE);
			return false;
		}
		long mediaSize;
		try {
			mediaSize = Files.size(stagedMediaPath);
		} catch (IOException e) {
			deleteQuietly(stagedMediaPath);
			showClientMessage(BlockChatStrings.FAILED_READ_CAPTURE);
			return false;
		}

		long optimisticSendId = BlockChatChatState.recordLocalOutgoing(
			targets.stream().map(target -> new BlockChatChatState.OutgoingTarget(target.uuid(), target.username(), target.skinUrl())).toList(),
			media.type() == PendingCapture.MediaType.VIDEO ? BlockChatChatState.MediaType.VIDEO : BlockChatChatState.MediaType.IMAGE
		);
		PendingSend pending = new PendingSend(
			stagedMediaPath,
			mediaType,
			contentType,
			mediaSize,
			List.copyOf(targets),
			optimisticSendId
		);
		if (!PENDING_SEND.compareAndSet(null, pending)) {
			deleteQuietly(stagedMediaPath);
			BlockChatChatState.rollbackOptimisticOutgoing(optimisticSendId);
			showClientMessage(BlockChatStrings.SEND_ALREADY_IN_PROGRESS);
			return false;
		}

		BlockChatWebSocket.sendSnap(
			targets.stream().map(SendTarget::uuid).toList(),
			mediaType,
			mediaSize,
			mediaDimensions[0],
			mediaDimensions[1],
			contentType,
			captionText,
			captionOffsetY,
			expiryMs
		);
		return true;
	}

	private static List<SendTarget> dedupeTargets(List<SendTarget> rawTargets) {
		Map<String, SendTarget> deduped = new LinkedHashMap<>();
		if (rawTargets == null) {
			return List.of();
		}
		for (SendTarget target : rawTargets) {
			if (target == null) {
				continue;
			}
			String uuid = BlockChatPlayerUuid.canonicalize(target.uuid());
			if (uuid == null) {
				continue;
			}
			deduped.putIfAbsent(uuid, new SendTarget(uuid, target.username(), target.skinUrl()));
		}
		return new ArrayList<>(deduped.values());
	}

	private static boolean allTargetsAreFriends(List<SendTarget> targets) {
		for (SendTarget target : targets) {
			if (BlockChatSocialState.relationshipFor(target.uuid()) != BlockChatSocialState.FriendRelationship.FRIENDS) {
				return false;
			}
		}
		return true;
	}

	private static void ensureListener() {
		if (listenerInstalled) {
			return;
		}
		BlockChatWebSocket.addMessageListener(WEBSOCKET_LISTENER);
		listenerInstalled = true;
	}

	private static void handleMessage(JsonObject message) {
		PendingSend pending = PENDING_SEND.get();
		if (pending == null || message == null || !message.has("type")) {
			return;
		}

		String type = message.get("type").getAsString();
		switch (type) {
			case "snap_upload_url" -> handleUploadUrl(message, pending);
			case "error" -> handleError(message, pending);
			case "snap_delivered" -> clearIfMatching(readString(message, "snap_id"));
			default -> {
			}
		}
	}

	private static void handleUploadUrl(JsonObject message, PendingSend pending) {
		String snapId = readString(message, "snap_id");
		String uploadUrl = readString(message, "upload_url");
		if (snapId == null || uploadUrl == null) {
			return;
		}
		if (!pending.claimUploadStart(snapId)) {
			return;
		}

		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(URI.create(uploadUrl))
				.timeout(Duration.ofMinutes(2))
				.header("Content-Type", pending.contentType())
				.PUT(HttpRequest.BodyPublishers.ofFile(pending.stagedMediaPath()))
				.build();
		} catch (IOException e) {
			BlockChatClientMod.LOGGER.warn("Failed to build BlockChat upload request", e);
			BlockChatDebugLog.line("send", "upload_request_build_failed snap_id=" + snapId, e);
			failPendingSend(BlockChatStrings.FAILED_READ_STAGED_CAPTURE);
			return;
		}

		HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.whenComplete((response, throwable) -> {
				if (throwable != null) {
					BlockChatClientMod.LOGGER.warn("BlockChat upload failed", throwable);
					BlockChatDebugLog.line(
						"send",
						"upload_put_failed snap_id=" + snapId
							+ " upload_url=" + BlockChatLogPrivacy.privacySafeUrlHost(uploadUrl),
						throwable
					);
					failPendingSend(BlockChatStrings.FAILED_UPLOAD_CAPTURE);
					return;
				}
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					BlockChatClientMod.LOGGER.warn("BlockChat upload failed with status {}", response.statusCode());
					BlockChatDebugLog.line(
						"send",
						"upload_put_http_error snap_id=" + snapId + " status=" + response.statusCode()
							+ " upload_url=" + BlockChatLogPrivacy.privacySafeUrlHost(uploadUrl)
					);
					failPendingSend(BlockChatStrings.FAILED_UPLOAD_CAPTURE);
					return;
				}

				BlockChatWebSocket.snapUploaded(snapId);
				clearIfMatching(snapId);
			});
	}

	private static void handleError(JsonObject message, PendingSend pending) {
		String code = readString(message, "code");
		if (code == null || !isSendError(code)) {
			return;
		}
		String snapId = readString(message, "ref_id");
		String pendingSnapId = pending.snapId();
		if (pendingSnapId != null && snapId != null && !pendingSnapId.equals(snapId)) {
			return;
		}
		String errorMessage = readString(message, "message");
		BlockChatDebugLog.line(
			"send",
			"server_send_error code=" + code
				+ " snap_id=" + (snapId != null ? snapId : "")
				+ " message=" + (errorMessage == null ? "" : errorMessage.replace('\n', ' '))
		);
		failPendingSend(errorMessage == null || errorMessage.isBlank()
			? BlockChatStrings.SEND_FAILED
			: errorMessage);
	}

	private static boolean isSendError(String code) {
		return "media_too_large".equals(code)
			|| "invalid_content_type".equals(code)
			|| "upload_rate_limited".equals(code)
			|| "invalid_recipient".equals(code)
			|| "snap_not_found".equals(code)
			|| "invalid_upload".equals(code)
			|| "delivery_failed".equals(code)
			|| "not_friends".equals(code)
			|| "user_not_found".equals(code);
	}

	private static void failPendingSend(String userMessage) {
		PendingSend pending = PENDING_SEND.getAndSet(null);
		if (pending != null) {
			deleteQuietly(pending.stagedMediaPath());
			Minecraft.getInstance().execute(() -> BlockChatChatState.rollbackOptimisticOutgoing(pending.optimisticSendId()));
		}
		showClientMessage(userMessage);
	}

	private static void clearIfMatching(String snapId) {
		PendingSend pending = PENDING_SEND.get();
		if (pending == null) {
			return;
		}
		String pendingSnapId = pending.snapId();
		if (pendingSnapId != null && snapId != null && !pendingSnapId.equals(snapId)) {
			return;
		}
		if (PENDING_SEND.compareAndSet(pending, null)) {
			deleteQuietly(pending.stagedMediaPath());
			BlockChatChatState.confirmOptimisticOutgoing(pending.optimisticSendId());
		}
	}

	private static Path stageMediaFile(PendingCapture.PendingMedia media) throws IOException {
		String extension = fileExtension(media.path());
		Path stagedPath = Files.createTempFile("blockchat-send-", extension);
		try {
			Files.move(media.path(), stagedPath, StandardCopyOption.REPLACE_EXISTING);
			return stagedPath;
		} catch (IOException moveFailure) {
			Files.copy(media.path(), stagedPath, StandardCopyOption.REPLACE_EXISTING);
			return stagedPath;
		}
	}

	private static String detectContentType(PendingCapture.MediaType mediaType, Path path) {
		String lowerName = path.getFileName().toString().toLowerCase();
		if (mediaType == PendingCapture.MediaType.VIDEO) {
			if (lowerName.endsWith(".webm")) {
				return "video/webm";
			}
			return "video/mp4";
		}
		if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lowerName.endsWith(".gif")) {
			return "image/gif";
		}
		return "image/png";
	}

	private static int[] resolveMediaDimensions(PendingCapture.PendingMedia media, Path stagedPath) throws IOException {
		if (media == null) {
			return new int[]{0, 0};
		}
		if (media.type() == PendingCapture.MediaType.VIDEO) {
			return new int[]{Math.max(0, media.videoWidth()), Math.max(0, media.videoHeight())};
		}
		try (var input = Files.newInputStream(stagedPath); NativeImage image = NativeImage.read(input)) {
			return new int[]{Math.max(0, image.getWidth()), Math.max(0, image.getHeight())};
		}
	}

	private static String fileExtension(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot) : "";
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private static void showClientMessage(String message) {
		Minecraft.getInstance().execute(() -> {
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(
					Component.literal(BlockChatStrings.prefixedMessage(message)),
					false
				);
			}
		});
	}

	private static String readString(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		return object.get(key).getAsString();
	}

	public record SendTarget(String uuid, String username, String skinUrl) {
	}

	private record PendingSend(
		Path stagedMediaPath,
		String mediaType,
		String contentType,
		long mediaSize,
		List<SendTarget> targets,
		long optimisticSendId,
		AtomicReference<String> claimedSnapId
	) {
		private PendingSend(
			Path stagedMediaPath,
			String mediaType,
			String contentType,
			long mediaSize,
			List<SendTarget> targets,
			long optimisticSendId
		) {
			this(stagedMediaPath, mediaType, contentType, mediaSize, targets, optimisticSendId, new AtomicReference<>());
		}

		private boolean claimUploadStart(String snapId) {
			return claimedSnapId.compareAndSet(null, snapId);
		}

		private String snapId() {
			return claimedSnapId.get();
		}
	}
}
