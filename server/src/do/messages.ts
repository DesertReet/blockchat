import type { BlockChatUserDurableObject } from './user-do';
import type { ClientMessage, ServerMessage } from '../ws-protocol';
import {
	MAX_MEDIA_SIZE,
	MAX_SNAP_RECIPIENTS,
	ALLOWED_CONTENT_TYPES,
	DEFAULT_PREFERENCES,
	PreferenceToggle,
	normalizeUuid,
} from '../ws-protocol';
import {
	createR2Client,
	generateUploadUrl,
	generateDownloadUrl,
	PRESIGN_EXPIRY,
	clampPresignExpirySeconds,
	expiryMsToPresignSeconds,
} from '../r2/presign';
import { AuthError, fetchMojangPublicProfile } from '../helpers';
import { errorSummary, testLog } from '../test-log';
import {
	MediaInspectionError,
	type DetectedMediaMetadata,
	inspectR2MediaObject,
} from '../media/inspect';

/** Preference keys and their allowed values */
const DEFAULT_EXPIRY_ALLOWED_VALUES = [3_600_000, 86_400_000, 259_200_000, 604_800_000] as const;
const DEFAULT_SEND_EXPIRY_MS = 86_400_000;
const PREFERENCE_DEFINITIONS = {
	default_expiry_ms: {
		allowedValues: DEFAULT_EXPIRY_ALLOWED_VALUES,
		defaultValue: DEFAULT_PREFERENCES.default_expiry_ms,
	},
	notifications: {
		allowedValues: [PreferenceToggle.Off, PreferenceToggle.On],
		defaultValue: DEFAULT_PREFERENCES.notifications,
	},
	friend_requests: {
		allowedValues: [PreferenceToggle.Off, PreferenceToggle.On],
		defaultValue: DEFAULT_PREFERENCES.friend_requests,
	},
} as const;

const MAX_CAPTION_LENGTH = 280;
const MIN_CAPTION_OFFSET_Y = -1;
const MAX_CAPTION_OFFSET_Y = 1;
const MAX_MEDIA_DIMENSION = 8_192;
const CONTENT_TYPE_TO_MEDIA_TYPE = new Map<string, 'image' | 'video'>([
	['image/png', 'image'],
	['image/jpeg', 'image'],
	['image/gif', 'image'],
	['video/mp4', 'video'],
	['video/webm', 'video'],
]);

function logDo(dobj: BlockChatUserDurableObject, event: string, fields?: Record<string, unknown>): void {
	testLog(dobj.bindings, 'do.messages', event, fields);
}

export async function handleClientMessage(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage
): Promise<void> {
	logDo(dobj, 'client_message', { type: msg.type });
	switch (msg.type) {
		case 'send_snap':
			return handleSendSnap(dobj, ws, msg);
		case 'snap_uploaded':
			return handleSnapUploaded(dobj, ws, msg);
		case 'view_snap':
			return handleViewSnap(dobj, ws, msg);
		case 'snap_viewed':
			return handleSnapViewed(dobj, ws, msg);
		case 'add_friend':
			return handleAddFriend(dobj, ws, msg);
		case 'accept_friend':
			return handleAcceptFriend(dobj, ws, msg);
		case 'remove_friend':
			return handleRemoveFriend(dobj, ws, msg);
		case 'get_friends':
			return handleGetFriends(dobj, ws);
		case 'get_snaps':
			return handleGetSnaps(dobj, ws);
		case 'get_chat_recents':
			return handleGetChatRecents(dobj, ws);
		case 'ping':
			send(ws, { type: 'pong' });
			return;
		case 'session_refresh':
			return handleSessionRefresh(dobj, ws);
		case 'session_revoke':
			return handleSessionRevoke(dobj, ws);
		case 'set_preference':
			return handleSetPreference(dobj, ws, msg);
		case 'get_preferences':
			return handleGetPreferences(dobj, ws);
		case 'search_user':
			return handleSearchUser(dobj, ws, msg);
		case 'delete_account':
			return handleDeleteAccount(dobj);
	}
}

function send(ws: WebSocket, msg: ServerMessage): void {
	ws.send(JSON.stringify(msg));
}

function sendError(ws: WebSocket, code: string, message: string, ref_id?: string): void {
	send(ws, { type: 'error', code, message, ref_id });
}

function getProfile(dobj: BlockChatUserDurableObject): { uuid: string; username: string; skinUrl: string | null } {
	const row = dobj.sql.exec('SELECT uuid, username, skin_url FROM profile LIMIT 1').one();
	return {
		uuid: normalizeUuid(row.uuid as string),
		username: row.username as string,
		skinUrl: (row.skin_url as string) || null,
	};
}

// ── Snap handlers ──

async function handleSendSnap(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'send_snap' }
): Promise<void> {
	logDo(dobj, 'send_snap_attempt', { to: msg.to, mediaType: msg.media_type, mediaSize: msg.media_size });
	const requestedMediaSize = normalizeRequestedMediaSize(msg.media_size);
	if (requestedMediaSize === null) {
		logDo(dobj, 'send_snap_rejected_invalid_media_size', { mediaSize: msg.media_size });
		return sendError(ws, 'invalid_media_size', 'media_size must be a positive integer greater than zero');
	}
	if (requestedMediaSize > MAX_MEDIA_SIZE) {
		logDo(dobj, 'send_snap_rejected_media_too_large', { mediaSize: requestedMediaSize });
		return sendError(ws, 'media_too_large', `Media size exceeds maximum of ${MAX_MEDIA_SIZE} bytes`);
	}

	if (!ALLOWED_CONTENT_TYPES.includes(msg.content_type as (typeof ALLOWED_CONTENT_TYPES)[number])) {
		logDo(dobj, 'send_snap_rejected_invalid_content_type', { contentType: msg.content_type });
		return sendError(ws, 'invalid_content_type', `Content type ${msg.content_type} is not allowed`);
	}
	const hintedMediaType = CONTENT_TYPE_TO_MEDIA_TYPE.get(msg.content_type);
	if (!hintedMediaType || msg.media_type !== hintedMediaType) {
		logDo(dobj, 'send_snap_rejected_media_type_mismatch', {
			mediaType: msg.media_type,
			contentType: msg.content_type,
		});
		return sendError(ws, 'invalid_media_type', 'media_type must match the provided content_type');
	}

	const expiryMs = resolveSnapExpiryMs(msg.expiry_ms);
	const captionText = normalizeCaptionText(msg.caption_text);
	const captionOffsetY = normalizeCaptionOffsetY(msg.caption_offset_y);
	const mediaWidth = normalizeMediaDimension(msg.media_width);
	const mediaHeight = normalizeMediaDimension(msg.media_height);
	if (mediaWidth === null || mediaHeight === null) {
		logDo(dobj, 'send_snap_rejected_invalid_media_dimensions', {
			mediaWidth: msg.media_width,
			mediaHeight: msg.media_height,
		});
		return sendError(ws, 'invalid_media_dimensions', 'media_width and media_height must be non-negative integers');
	}

	if (!dobj.consumeUploadSlot()) {
		logDo(dobj, 'send_snap_rate_limited');
		return sendError(ws, 'upload_rate_limited', 'Upload limit exceeded. Please try again later.');
	}

	let keepUploadReservation = false;

	const profile = getProfile(dobj);
	const recipientUuids = normalizeRecipientUuids(msg.to);
	if (recipientUuids === null) {
		logDo(dobj, 'send_snap_rejected_invalid_recipient_uuid', { to: msg.to });
		return sendError(ws, 'invalid_recipient', 'Recipients must be valid Minecraft UUIDs');
	}
	if (recipientUuids.length === 0) {
		logDo(dobj, 'send_snap_rejected_no_recipients');
		return sendError(ws, 'invalid_recipient', 'Select at least one recipient');
	}
	if (recipientUuids.length > MAX_SNAP_RECIPIENTS) {
		logDo(dobj, 'send_snap_rejected_too_many_recipients', { count: recipientUuids.length });
		return sendError(ws, 'too_many_recipients', `You can only send to ${MAX_SNAP_RECIPIENTS} recipients at once`);
	}

	if (recipientUuids.some((u) => u === profile.uuid)) {
		logDo(dobj, 'send_snap_rejected_self', { self: profile.uuid });
		return sendError(ws, 'cannot_send_to_self', 'You cannot send snaps to yourself');
	}

	let uploadId = '';
	try {
		const sentAtIso = new Date().toISOString();
		const expiresAtIso = new Date(Date.now() + expiryMs).toISOString();
		uploadId = crypto.randomUUID();
		const r2Key = `snaps/${profile.uuid}/${uploadId}`;
		const recipientRows: Array<{
			snapId: string;
			toUuid: string;
			username: string;
			skinUrl: string | null;
		}> = [];

		for (const toUuid of recipientUuids) {
			if (!dobj.isAcceptedFriend(toUuid)) {
				logDo(dobj, 'send_snap_sender_not_friend', { to: toUuid });
				return sendError(ws, 'not_friends', 'You can only send snaps to accepted friends');
			}
			const recipientId = dobj.bindings.USER_DO.idFromName(toUuid);
			const recipientStub = dobj.bindings.USER_DO.get(recipientId);
			const recipient = await recipientStub.getSendSnapTarget(profile.uuid);
			if (recipient.status === 'user_not_found') {
				logDo(dobj, 'send_snap_recipient_not_found', { to: toUuid });
				return sendError(ws, 'user_not_found', 'User not found');
			}
			if (recipient.status === 'not_friends') {
				logDo(dobj, 'send_snap_recipient_not_friend', { to: toUuid });
				return sendError(ws, 'not_friends', 'You can only send snaps to accepted friends');
			}
			recipientRows.push({
				snapId: crypto.randomUUID(),
				toUuid,
				username: recipient.username,
				skinUrl: recipient.skin_url,
			});
		}

		const admissionFailure = dobj.getUploadAdmissionFailure(requestedMediaSize);
		if (admissionFailure) {
			logDo(dobj, 'send_snap_rejected_upload_admission', {
				code: admissionFailure.code,
				reservedSize: requestedMediaSize,
			});
			return sendError(ws, admissionFailure.code, admissionFailure.message);
		}
		dobj.recordPendingUpload(uploadId, r2Key, msg.media_type, msg.content_type, requestedMediaSize);

		const client = createR2Client(dobj.bindings);
		const uploadUrl = await generateUploadUrl(client, dobj.bindings, r2Key, msg.content_type, requestedMediaSize);

		dobj.sql.exec(
			`INSERT INTO snap_metadata (
				upload_id,
				from_uuid,
				media_type,
				content_type,
				media_size,
				media_width,
				media_height,
				caption_text,
				caption_offset_y,
				expiry_ms,
				sent_at,
				expires_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			uploadId,
			profile.uuid,
			msg.media_type,
			msg.content_type,
			requestedMediaSize,
			mediaWidth,
			mediaHeight,
			captionText,
			captionOffsetY,
			expiryMs,
			sentAtIso,
			expiresAtIso
		);

		for (const recipient of recipientRows) {
			dobj.sql.exec(
				`INSERT INTO sent_snaps (
					snap_id,
					upload_id,
					to_uuid,
					to_username,
					to_skin_url,
					media_type,
					r2_key,
					content_type,
					media_size,
					sent_at
				)
				 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				recipient.snapId,
				uploadId,
				recipient.toUuid,
				recipient.username,
				recipient.skinUrl,
				msg.media_type,
				r2Key,
				msg.content_type,
				requestedMediaSize,
				sentAtIso
			);
		}

		keepUploadReservation = true;
		logDo(dobj, 'send_snap_upload_url_issued', { snapId: uploadId, recipientCount: recipientRows.length });
		send(ws, { type: 'snap_upload_url', snap_id: uploadId, upload_url: uploadUrl, expires_in: PRESIGN_EXPIRY });
	} finally {
		if (!keepUploadReservation) {
			dobj.releaseUploadSlot();
			if (uploadId) {
				dobj.abandonPendingUpload(uploadId);
			}
		}
	}
}

async function handleSnapUploaded(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'snap_uploaded' }
): Promise<void> {
	logDo(dobj, 'snap_uploaded_attempt', { snapId: msg.snap_id });
	const snaps = dobj.sql
		.exec(
			`SELECT *
			 FROM sent_snaps
			 WHERE upload_id = ?
			   AND delivered_at IS NULL
			   AND dropped_at IS NULL`,
			msg.snap_id
		)
		.toArray();
	if (snaps.length === 0) {
		logDo(dobj, 'snap_uploaded_not_found', { snapId: msg.snap_id });
		return sendError(ws, 'snap_not_found', 'Snap not found or already delivered');
	}

	const firstSnap = snaps[0];
	const profile = getProfile(dobj);
	const metadataRow = dobj.sql
		.exec(
			`SELECT content_type, media_size, media_width, media_height, caption_text, caption_offset_y, expiry_ms, expires_at
			 FROM snap_metadata
			 WHERE upload_id = ?
			 LIMIT 1`,
			msg.snap_id
		)
		.toArray()[0];
	const captionText = typeof metadataRow?.caption_text === 'string' ? (metadataRow.caption_text as string) : '';
	const captionOffsetY = Number.isFinite(Number(metadataRow?.caption_offset_y)) ? Number(metadataRow.caption_offset_y) : 0;
	const expiryMs = Number.isFinite(Number(metadataRow?.expiry_ms))
		? Number(metadataRow.expiry_ms)
		: DEFAULT_PREFERENCES.default_expiry_ms;
	const expiresAt = typeof metadataRow?.expires_at === 'string'
		? (metadataRow.expires_at as string)
		: new Date(Date.now() + expiryMs).toISOString();

	// Deliver to recipient via DO-to-DO RPC
	try {
		const uploadValidation = await validateUploadedSnapObject(dobj, firstSnap);
		if (!uploadValidation.ok) {
			dobj.recordInvalidUpload(msg.snap_id, uploadValidation.reason, uploadValidation.actualSize);
			logDo(dobj, 'snap_uploaded_validation_failed', { snapId: msg.snap_id, reason: uploadValidation.reason });
			await deleteUndeliveredUpload(dobj, msg.snap_id, firstSnap.r2_key as string);
			return sendError(ws, 'invalid_upload', uploadValidation.reason, msg.snap_id);
		}
		const authoritativeMetadata = uploadValidation.metadata;
		persistAuthoritativeUploadMetadata(dobj, msg.snap_id, authoritativeMetadata);
		dobj.markUploadValidated(
			msg.snap_id,
			authoritativeMetadata.mediaType,
			authoritativeMetadata.contentType,
			authoritativeMetadata.mediaSize,
			authoritativeMetadata.width,
			authoritativeMetadata.height
		);

		let deliveredCount = 0;
		let droppedCount = 0;

		for (const snap of snaps) {
			const toUuid = normalizeUuid(snap.to_uuid as string);
			if (toUuid === profile.uuid) {
				logDo(dobj, 'snap_uploaded_rejected_self_recipient', { snapId: snap.snap_id as string });
				dobj.sql.exec("UPDATE sent_snaps SET dropped_at = datetime('now') WHERE snap_id = ?", snap.snap_id as string);
				droppedCount++;
				continue;
			}
			const recipientId = dobj.bindings.USER_DO.idFromName(toUuid);
			const recipientStub = dobj.bindings.USER_DO.get(recipientId);
			const result = await recipientStub.receiveSnap({
				snap_id: snap.snap_id as string,
				upload_id: snap.upload_id as string,
				from_uuid: profile.uuid,
				from_username: profile.username,
				from_skin_url: profile.skinUrl,
				media_type: authoritativeMetadata.mediaType,
				r2_key: snap.r2_key as string,
				content_type: authoritativeMetadata.contentType,
				media_size: authoritativeMetadata.mediaSize,
				media_width: authoritativeMetadata.width,
				media_height: authoritativeMetadata.height,
				caption_text: captionText,
				caption_offset_y: captionOffsetY,
				expiry_ms: expiryMs,
				expires_at: expiresAt,
				sent_at: snap.sent_at as string,
			});

			if (result === 'rejected') {
				dobj.sql.exec("UPDATE sent_snaps SET dropped_at = datetime('now') WHERE snap_id = ?", snap.snap_id as string);
				droppedCount++;
				continue;
			}

			dobj.sql.exec("UPDATE sent_snaps SET delivered_at = datetime('now') WHERE snap_id = ?", snap.snap_id as string);
			deliveredCount++;
		}

		logDo(dobj, 'snap_uploaded_delivered', {
			snapId: msg.snap_id,
			deliveredCount,
			droppedCount,
		});
		send(ws, { type: 'snap_delivered', snap_id: msg.snap_id });
	} catch (err) {
		logDo(dobj, 'snap_uploaded_delivery_error', { snapId: msg.snap_id, ...errorSummary(err) });
		sendError(ws, 'delivery_failed', 'Failed to deliver snap to recipient', msg.snap_id);
	}
}

async function handleViewSnap(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'view_snap' }
): Promise<void> {
	logDo(dobj, 'view_snap_attempt', { snapId: msg.snap_id });
	const snap = dobj.sql
		.exec(
			`SELECT
				rs.snap_id,
				rs.upload_id,
				rs.from_uuid,
				rs.r2_key,
				rs.media_type,
				rs.sent_at,
				sm.content_type,
				sm.media_width,
				sm.media_height,
				sm.caption_text,
				sm.caption_offset_y,
				sm.expiry_ms,
				sm.expires_at
			FROM received_snaps rs
			LEFT JOIN snap_metadata sm ON sm.upload_id = rs.upload_id
			WHERE rs.snap_id = ?
			  AND rs.viewed_at IS NULL
			LIMIT 1`,
			msg.snap_id
		)
		.toArray();
	if (snap.length === 0) {
		logDo(dobj, 'view_snap_not_found', { snapId: msg.snap_id });
		return sendError(ws, 'snap_not_found', 'Snap not found or already viewed');
	}

	const snapRow = snap[0];
	const snapExpiryMs = Number.isFinite(Number(snapRow.expiry_ms))
		? Number(snapRow.expiry_ms)
		: DEFAULT_PREFERENCES.default_expiry_ms;
	const expiresAtRaw = typeof snapRow.expires_at === 'string' ? (snapRow.expires_at as string) : null;
	const nowMs = Date.now();
	const expiresAtMs = parseTimestampMs(expiresAtRaw);
	if (Number.isFinite(expiresAtMs) && expiresAtMs <= nowMs) {
		logDo(dobj, 'view_snap_expired', { snapId: msg.snap_id, uploadId: snapRow.upload_id as string });
		const uploadId = snapRow.upload_id as string;
		await dobj.markUploadExpired(uploadId);
		try {
			const senderUuid = normalizeUuid(snapRow.from_uuid as string);
			const senderId = dobj.bindings.USER_DO.idFromName(senderUuid);
			const senderStub = dobj.bindings.USER_DO.get(senderId) as { markUploadExpired?: (uploadId: string) => Promise<void> };
			if (typeof senderStub.markUploadExpired === 'function') {
				await senderStub.markUploadExpired(uploadId);
			}
		} catch (err) {
			logDo(dobj, 'view_snap_expired_sender_reconcile_error', { snapId: msg.snap_id, ...errorSummary(err) });
		}
		return sendError(ws, 'snap_expired', 'Snap has expired');
	}

	const remainingMs = Number.isFinite(expiresAtMs) ? Math.max(1_000, expiresAtMs - nowMs) : snapExpiryMs;
	const requestedExpiresIn = expiryMsToPresignSeconds(Math.min(snapExpiryMs, remainingMs));
	const actualExpiresIn = clampPresignExpirySeconds(requestedExpiresIn);
	const client = createR2Client(dobj.bindings);
	const downloadUrl = await generateDownloadUrl(
		client,
		dobj.bindings,
		snapRow.r2_key as string,
		actualExpiresIn
	);

	send(ws, {
		type: 'snap_download_url',
		snap_id: msg.snap_id,
		upload_id: snapRow.upload_id as string,
		download_url: downloadUrl,
		expires_in: actualExpiresIn,
		media_type: snapRow.media_type as 'image' | 'video',
		content_type: typeof snapRow.content_type === 'string' ? (snapRow.content_type as string) : '',
		media_width: Number.isFinite(Number(snapRow.media_width)) ? Number(snapRow.media_width) : 0,
		media_height: Number.isFinite(Number(snapRow.media_height)) ? Number(snapRow.media_height) : 0,
		caption_text: typeof snapRow.caption_text === 'string' ? (snapRow.caption_text as string) : '',
		caption_offset_y: Number.isFinite(Number(snapRow.caption_offset_y)) ? Number(snapRow.caption_offset_y) : 0,
		expiry_ms: snapExpiryMs,
		sent_at: snapRow.sent_at as string,
	});

	logDo(dobj, 'view_snap_download_url_issued', {
		snapId: msg.snap_id,
		uploadId: snapRow.upload_id as string,
		expiresIn: actualExpiresIn,
	});
}

async function handleSnapViewed(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'snap_viewed' }
): Promise<void> {
	logDo(dobj, 'snap_viewed_attempt', { snapId: msg.snap_id });
	const snap = dobj.sql
		.exec('SELECT from_uuid, upload_id FROM received_snaps WHERE snap_id = ? AND viewed_at IS NULL', msg.snap_id)
		.toArray();
	if (snap.length === 0) {
		logDo(dobj, 'snap_viewed_noop_not_found', { snapId: msg.snap_id });
		return;
	}

	const s = snap[0];
	dobj.sql.exec("UPDATE received_snaps SET viewed_at = datetime('now') WHERE snap_id = ?", msg.snap_id);

	// Notify sender that snap was opened
	try {
		const profile = getProfile(dobj);
		const senderId = dobj.bindings.USER_DO.idFromName(normalizeUuid(s.from_uuid as string));
		const senderStub = dobj.bindings.USER_DO.get(senderId);
		await senderStub.snapOpened(String(s.upload_id), profile.uuid);
		logDo(dobj, 'snap_viewed_notified_sender', { snapId: msg.snap_id, senderUuid: s.from_uuid as string });
	} catch (err) {
		logDo(dobj, 'snap_viewed_notify_sender_error', { snapId: msg.snap_id, ...errorSummary(err) });
	}
}

// ── Friend handlers ──

async function handleAddFriend(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'add_friend' }
): Promise<void> {
	const profile = getProfile(dobj);
	const targetUuid = normalizeUuid(msg.uuid);
	logDo(dobj, 'add_friend_attempt', { from: profile.uuid, to: targetUuid });

	if (targetUuid === profile.uuid) {
		logDo(dobj, 'add_friend_rejected_self', { to: targetUuid });
		return sendError(ws, 'invalid_friend', 'You cannot add yourself as a friend');
	}

	// Check if already exists locally
	const existing = dobj.sql
		.exec('SELECT status FROM friends WHERE friend_uuid = ?', targetUuid)
		.toArray();
	if (existing.length > 0) {
		logDo(dobj, 'add_friend_rejected_existing', { to: targetUuid, status: existing[0].status as string });
		return sendError(ws, 'already_exists', `Friend request already exists (status: ${existing[0].status})`);
	}

	// Single RPC: check profile, check enabled, insert if allowed
	try {
		const recipientId = dobj.bindings.USER_DO.idFromName(targetUuid);
		const recipientStub = dobj.bindings.USER_DO.get(recipientId);
		const result = await recipientStub.tryFriendRequest(profile.uuid, profile.username, profile.skinUrl);

		if (result.status === 'user_not_found') {
			logDo(dobj, 'add_friend_recipient_not_found', { to: targetUuid });
			return sendError(ws, 'user_not_found', 'User not found');
		}
		if (result.status === 'disabled') {
			logDo(dobj, 'add_friend_recipient_disabled', { to: targetUuid });
			return sendError(ws, 'friend_requests_disabled', 'This user is not accepting friend requests');
		}

		// 'sent' or 'already_exists' on recipient side — record the outgoing request locally
		dobj.sql.exec(
			`INSERT INTO friends (friend_uuid, friend_username, friend_skin_url, status)
			 VALUES (?, ?, ?, 'pending_outgoing')`,
			targetUuid,
			result.username,
			result.skin_url
		);

		send(ws, {
			type: 'friend_request_sent',
			uuid: targetUuid,
			username: result.username,
		});
		logDo(dobj, 'add_friend_recorded', { to: targetUuid });
	} catch (err) {
		logDo(dobj, 'add_friend_error', { to: targetUuid, ...errorSummary(err) });
		sendError(ws, 'friend_request_failed', 'Failed to send friend request');
	}
}

async function handleSearchUser(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'search_user' }
): Promise<void> {
	const query = typeof msg.username === 'string' ? msg.username.trim() : '';
	logDo(dobj, 'search_user_attempt', { queryLength: query.length });
	if (!query) {
		logDo(dobj, 'search_user_invalid_username');
		return sendError(ws, 'invalid_username', 'username must be a non-empty string');
	}

	if (!dobj.consumeUserSearchSlot()) {
		logDo(dobj, 'search_user_rate_limited');
		return sendError(ws, 'search_rate_limited', 'Search limit exceeded. Please try again later.');
	}

	try {
		const result = await fetchMojangPublicProfile(query);
		const resolvedUuid = normalizeUuid(result.uuid);
		send(ws, {
			type: 'user_search_result',
			query,
			user: {
				uuid: resolvedUuid,
				username: result.username,
				skin_url: result.skinUrl,
			},
		});
		logDo(dobj, 'search_user_found', { query, uuid: resolvedUuid });
	} catch (err) {
		if (err instanceof AuthError && err.step === 'unknown_username') {
			logDo(dobj, 'search_user_not_found', { query });
			send(ws, { type: 'user_search_result', query, user: null });
			return;
		}
		if (err instanceof AuthError) {
			logDo(dobj, 'search_user_auth_error', { query, step: err.step, status: err.status });
			sendError(ws, err.step, err.message);
			return;
		}
		logDo(dobj, 'search_user_unhandled_error', { query, ...errorSummary(err) });
		throw err;
	}
}

async function handleAcceptFriend(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'accept_friend' }
): Promise<void> {
	const targetUuid = normalizeUuid(msg.uuid);
	logDo(dobj, 'accept_friend_attempt', { targetUuid });
	const existing = dobj.sql
		.exec("SELECT friend_username FROM friends WHERE friend_uuid = ? AND status = 'pending_incoming'", targetUuid)
		.toArray();
	if (existing.length === 0) {
		logDo(dobj, 'accept_friend_missing_pending', { targetUuid });
		return sendError(ws, 'no_pending_request', 'No pending friend request from this user');
	}

	const profile = getProfile(dobj);

	// Update local status
	dobj.sql.exec("UPDATE friends SET status = 'accepted' WHERE friend_uuid = ?", targetUuid);

	// Notify requester via RPC
	try {
		const requesterId = dobj.bindings.USER_DO.idFromName(targetUuid);
		const requesterStub = dobj.bindings.USER_DO.get(requesterId);

		const skinUrl = dobj.sql.exec('SELECT skin_url FROM profile LIMIT 1').one().skin_url as string | null;
		await requesterStub.friendAccepted(profile.uuid, profile.username, skinUrl);
		logDo(dobj, 'accept_friend_notified_requester', { targetUuid });
	} catch (err) {
		logDo(dobj, 'accept_friend_notify_error', { targetUuid, ...errorSummary(err) });
	}

	send(ws, {
		type: 'friend_added',
		uuid: targetUuid,
		username: existing[0].friend_username as string,
	});
	logDo(dobj, 'accept_friend_completed', { targetUuid });
}

async function handleRemoveFriend(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'remove_friend' }
): Promise<void> {
	const targetUuid = normalizeUuid(msg.uuid);
	logDo(dobj, 'remove_friend_attempt', { targetUuid });
	const existing = dobj.sql
		.exec('SELECT 1 FROM friends WHERE friend_uuid = ?', targetUuid)
		.toArray();
	if (existing.length === 0) {
		logDo(dobj, 'remove_friend_not_found', { targetUuid });
		return sendError(ws, 'not_friends', 'Not in your friend list');
	}

	dobj.sql.exec('DELETE FROM friends WHERE friend_uuid = ?', targetUuid);

	// Notify the other user
	try {
		const profile = getProfile(dobj);
		const otherId = dobj.bindings.USER_DO.idFromName(targetUuid);
		const otherStub = dobj.bindings.USER_DO.get(otherId);
		await otherStub.friendRemoved(profile.uuid);
		logDo(dobj, 'remove_friend_notified_other', { targetUuid });
	} catch (err) {
		logDo(dobj, 'remove_friend_notify_error', { targetUuid, ...errorSummary(err) });
	}

	send(ws, { type: 'friend_removed', uuid: targetUuid });
	logDo(dobj, 'remove_friend_completed', { targetUuid });
}

function handleGetFriends(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	const friends = dobj.getFriendsSnapshot();
	logDo(dobj, 'get_friends', { count: friends.length });
	send(ws, { type: 'friend_list', friends });
}

// ── Snap list ──

function handleGetSnaps(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	const snaps = dobj.getUnreadSnapsSnapshot();
	logDo(dobj, 'get_snaps', { count: snaps.length });
	send(ws, {
		type: 'snap_list',
		snaps,
	});
}

function handleGetChatRecents(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	const recents = dobj.getChatRecentsSnapshot();
	logDo(dobj, 'get_chat_recents', { count: recents.length });
	send(ws, { type: 'chat_recents', recents });
}

// ── Session handlers ──

function handleSessionRefresh(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	const bytes = new Uint8Array(32);
	crypto.getRandomValues(bytes);
	const newToken = Array.from(bytes)
		.map((b) => b.toString(16).padStart(2, '0'))
		.join('');

	const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

	// Invalidate all existing sessions and create new one
	dobj.sql.exec('DELETE FROM sessions');
	dobj.sql.exec('INSERT INTO sessions (token, expires_at) VALUES (?, ?)', newToken, expiresAt);
	logDo(dobj, 'session_refresh');

	send(ws, { type: 'session_token', token: newToken, expires_at: expiresAt });
}

function handleSessionRevoke(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	dobj.sql.exec('DELETE FROM sessions');
	logDo(dobj, 'session_revoke');
	ws.close(1000, 'Session revoked');
}

function handleSetPreference(
	dobj: BlockChatUserDurableObject,
	ws: WebSocket,
	msg: ClientMessage & { type: 'set_preference' }
): void {
	if (!(msg.key in PREFERENCE_DEFINITIONS)) {
		logDo(dobj, 'set_preference_invalid_key', { key: msg.key });
		return sendError(ws, 'invalid_preference', `Unknown preference key: ${msg.key}`);
	}

	const definition = PREFERENCE_DEFINITIONS[msg.key];
	if (msg.key === 'default_expiry_ms') {
		dobj.sql.exec(
			`INSERT INTO preferences (key, value)
			 VALUES (?, ?)
			 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
			msg.key,
			String(DEFAULT_SEND_EXPIRY_MS)
		);

		send(ws, { type: 'preference_set', key: msg.key, value: DEFAULT_SEND_EXPIRY_MS });
		logDo(dobj, 'set_preference_forced_default_expiry', {
			requestedValue: msg.value,
			value: DEFAULT_SEND_EXPIRY_MS,
		});
		return;
	}

	const allowedValues = definition.allowedValues as readonly number[];
	if (!Number.isInteger(msg.value) || !allowedValues.includes(msg.value)) {
		logDo(dobj, 'set_preference_invalid_value', { key: msg.key, value: msg.value });
		return sendError(ws, 'invalid_preference', `Invalid value ${msg.value} for preference ${msg.key}`);
	}

	dobj.sql.exec(
		`INSERT INTO preferences (key, value)
		 VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		msg.key,
		String(msg.value)
	);

	send(ws, { type: 'preference_set', key: msg.key, value: msg.value });
	logDo(dobj, 'set_preference', { key: msg.key, value: msg.value });
}

function handleGetPreferences(dobj: BlockChatUserDurableObject, ws: WebSocket): void {
	send(ws, { type: 'preferences', preferences: dobj.getPreferencesSnapshot() });
	logDo(dobj, 'get_preferences');
}

async function handleDeleteAccount(dobj: BlockChatUserDurableObject): Promise<void> {
	logDo(dobj, 'delete_account_requested');
	await dobj.deleteAccount();
}

async function deleteUndeliveredUpload(dobj: BlockChatUserDurableObject, uploadId: string, r2Key: string): Promise<void> {
	try {
		await dobj.bindings.SNAPS_BUCKET.delete(r2Key);
	} catch (err) {
		logDo(dobj, 'delete_undelivered_snap_error', { snapId: uploadId, ...errorSummary(err) });
	}

	dobj.sql.exec('DELETE FROM sent_snaps WHERE upload_id = ?', uploadId);
	dobj.sql.exec('DELETE FROM snap_metadata WHERE upload_id = ?', uploadId);
}

function persistAuthoritativeUploadMetadata(
	dobj: BlockChatUserDurableObject,
	uploadId: string,
	metadata: ValidatedUploadedSnap
): void {
	dobj.sql.exec(
		`UPDATE snap_metadata
		 SET media_type = ?,
		     content_type = ?,
		     media_size = ?,
		     media_width = ?,
		     media_height = ?
		 WHERE upload_id = ?`,
		metadata.mediaType,
		metadata.contentType,
		metadata.mediaSize,
		metadata.width,
		metadata.height,
		uploadId
	);
	dobj.sql.exec(
		`UPDATE sent_snaps
		 SET media_type = ?,
		     content_type = ?,
		     media_size = ?
		 WHERE upload_id = ?`,
		metadata.mediaType,
		metadata.contentType,
		metadata.mediaSize,
		uploadId
	);
}

type ValidatedUploadedSnap = DetectedMediaMetadata & {
	mediaSize: number;
};

type UploadValidationResult =
	| { ok: true; metadata: ValidatedUploadedSnap }
	| { ok: false; reason: string; actualSize: number | null };

async function validateUploadedSnapObject(
	dobj: BlockChatUserDurableObject,
	snap: Record<string, ArrayBuffer | string | number | null>
): Promise<UploadValidationResult> {
	const object = await dobj.bindings.SNAPS_BUCKET.head(snap.r2_key as string);
	if (!object) {
		return { ok: false, reason: 'Uploaded media was not found in storage', actualSize: null };
	}
	if (object.size <= 0) {
		return { ok: false, reason: 'Uploaded media was empty', actualSize: object.size };
	}
	if (object.size > MAX_MEDIA_SIZE) {
		return {
			ok: false,
			reason: `Uploaded media size ${object.size} exceeds the maximum of ${MAX_MEDIA_SIZE} bytes`,
			actualSize: object.size,
		};
	}

	const expectedSize = Number(snap.media_size);
	if (Number.isFinite(expectedSize) && expectedSize > 0 && object.size !== expectedSize) {
		return {
			ok: false,
			reason: `Uploaded media size ${object.size} did not match expected size ${expectedSize}`,
			actualSize: object.size,
		};
	}

	try {
		const detected = await inspectR2MediaObject(dobj.bindings.SNAPS_BUCKET, snap.r2_key as string, object.size);
		await ensureUploadedObjectContentType(dobj, snap.r2_key as string, object.httpMetadata?.contentType ?? null, detected.contentType);
		return {
			ok: true,
			metadata: {
				...detected,
				mediaSize: object.size,
			},
		};
	} catch (err) {
		if (err instanceof MediaInspectionError) {
			return { ok: false, reason: err.message, actualSize: object.size };
		}
		throw err;
	}
}

async function ensureUploadedObjectContentType(
	dobj: BlockChatUserDurableObject,
	r2Key: string,
	currentContentType: string | null,
	authoritativeContentType: string
): Promise<void> {
	if (currentContentType === authoritativeContentType) {
		return;
	}

	const object = await dobj.bindings.SNAPS_BUCKET.get(r2Key);
	if (!object) {
		throw new MediaInspectionError('Uploaded media was not found in storage');
	}

	await dobj.bindings.SNAPS_BUCKET.put(r2Key, object.body, {
		httpMetadata: {
			...(object.httpMetadata ?? {}),
			contentType: authoritativeContentType,
		},
		customMetadata: object.customMetadata,
	});
}

function normalizeRecipientUuids(rawRecipients: string[]): string[] | null {
	if (!Array.isArray(rawRecipients)) {
		return null;
	}
	const uniqueRecipients = new Set<string>();
	for (const rawRecipient of rawRecipients) {
		if (typeof rawRecipient !== 'string') {
			return null;
		}
		const recipientUuid = normalizeUuid(rawRecipient);
		if (!/^[0-9a-f]{32}$/.test(recipientUuid)) {
			return null;
		}
		uniqueRecipients.add(recipientUuid);
	}
	return [...uniqueRecipients];
}

function resolveSnapExpiryMs(requestedExpiryMs: number | undefined): number {
	return DEFAULT_SEND_EXPIRY_MS;
}

function normalizeRequestedMediaSize(rawSize: number): number | null {
	if (!Number.isInteger(rawSize) || rawSize <= 0) {
		return null;
	}
	return rawSize;
}

function normalizeCaptionText(rawCaption: string | undefined): string {
	if (typeof rawCaption !== 'string') {
		return '';
	}
	const withoutNulls = rawCaption.replace(/\u0000/g, '');
	return withoutNulls.length > MAX_CAPTION_LENGTH ? withoutNulls.slice(0, MAX_CAPTION_LENGTH) : withoutNulls;
}

function normalizeCaptionOffsetY(rawOffsetY: number | undefined): number {
	if (typeof rawOffsetY !== 'number' || !Number.isFinite(rawOffsetY)) {
		return 0;
	}
	return Math.max(MIN_CAPTION_OFFSET_Y, Math.min(MAX_CAPTION_OFFSET_Y, rawOffsetY));
}

function normalizeMediaDimension(rawDimension: number | undefined): number | null {
	if (rawDimension == null) {
		return 0;
	}
	if (typeof rawDimension !== 'number' || !Number.isFinite(rawDimension) || rawDimension < 0) {
		return null;
	}
	return Math.max(0, Math.min(MAX_MEDIA_DIMENSION, Math.floor(rawDimension)));
}

function parseTimestampMs(rawTimestamp: string | null): number {
	if (!rawTimestamp) {
		return Number.NaN;
	}
	const firstTry = Date.parse(rawTimestamp);
	if (Number.isFinite(firstTry)) {
		return firstTry;
	}
	const normalized = rawTimestamp.includes('T') ? rawTimestamp : `${rawTimestamp.replace(' ', 'T')}Z`;
	return Date.parse(normalized);
}
