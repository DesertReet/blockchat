import { DurableObject } from 'cloudflare:workers';
import { migrateDatabase } from './schema';
import { handleClientMessage } from './messages';
import { errorSummary, testErrorLog, testLog } from '../test-log';
import { DEFAULT_PREFERENCES, PreferenceToggle, normalizeUuid } from '../ws-protocol';
import { AuthError, fetchMojangPublicProfileByUuid } from '../helpers';
import type { ClientMessage, ServerMessage } from '../ws-protocol';

export interface SnapDelivery {
	snap_id: string;
	upload_id: string;
	from_uuid: string;
	from_username: string;
	from_skin_url: string | null;
	media_type: 'image' | 'video';
	r2_key: string;
	content_type: string;
	media_size: number;
	media_width: number;
	media_height: number;
	caption_text: string;
	caption_offset_y: number;
	expiry_ms: number;
	expires_at: string;
	sent_at: string;
}

type PreferenceKey = keyof typeof DEFAULT_PREFERENCES;
type ChatRecentEntry = {
	uuid: string;
	username: string;
	skin_url: string | null;
	last_direction: 'sent' | 'received';
	last_media_type: 'image' | 'video';
	last_timestamp: string;
	last_activity_type: 'sent' | 'received' | 'opened' | 'dropped';
	last_activity_timestamp: string;
	incoming_unopened_count: number;
	incoming_unopened_media_type: 'image' | 'video' | null;
	incoming_unopened_timestamp: string | null;
	outgoing_unopened_count: number;
	outgoing_unopened_media_type: 'image' | 'video' | null;
	outgoing_unopened_timestamp: string | null;
};

type PendingStatusSummary = {
	count: number;
	media_type: 'image' | 'video';
	timestamp: string;
};

type ActivitySummary = {
	type: 'sent' | 'received' | 'opened' | 'dropped';
	timestamp: string;
};

type SnapInboxEntry = {
	snap_id: string;
	upload_id: string;
	from: string;
	from_username: string;
	from_skin_url: string | null;
	media_type: 'image' | 'video';
	content_type: string;
	caption_text: string;
	caption_offset_y: number;
	expiry_ms: number;
	media_width: number;
	media_height: number;
	sent_at: string;
	sender_unread_position: number;
	sender_unread_total: number;
	sender_oldest_unread_snap_id: string;
	sender_next_unread_snap_id: string | null;
};

export type TryFriendRequestResult =
	| { status: 'sent'; username: string; skin_url: string | null }
	| { status: 'already_exists'; username: string; skin_url: string | null }
	| { status: 'user_not_found' }
	| { status: 'disabled' };

export type SnapSendTargetResult =
	| { status: 'ready'; username: string; skin_url: string | null }
	| { status: 'not_friends' }
	| { status: 'user_not_found' };

/** Cleanup alarm fires every 6 hours. */
const CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000;
const PENDING_SNAP_DELETE_STORAGE_KEY = 'pending_snap_delete_deadlines';

type PendingSnapDeleteDeadlines = Record<string, number>;
type UploadAttemptStatus = 'pending' | 'validated' | 'invalid' | 'abandoned';
type UploadAttemptRow = {
	upload_id: string;
	r2_key: string;
	hint_media_type: 'image' | 'video';
	hint_content_type: string;
	reserved_size: number;
	actual_media_type: 'image' | 'video' | null;
	actual_content_type: string | null;
	actual_size: number | null;
	media_width: number;
	media_height: number;
	status: UploadAttemptStatus;
	last_error: string;
	created_at: string;
	updated_at: string;
	expires_at: string;
};
type UploadAdmissionFailure = {
	code: string;
	message: string;
};
type AbuseControlsRow = {
	suspended_until: string | null;
	last_reason: string;
	updated_at: string | null;
};

export class BlockChatUserDurableObject extends DurableObject<Env> {
	/** Max `search_user` messages per rolling minute for this player's DO. */
	private static readonly USER_SEARCH_LIMIT = 30;
	private static readonly USER_SEARCH_WINDOW_MS = 60_000;

	/** Max snap uploads per rolling minute. */
	private static readonly UPLOAD_LIMIT = 10;
	private static readonly UPLOAD_WINDOW_MS = 60_000;
	private static readonly PENDING_UPLOAD_LIMIT = 3;
	private static readonly UPLOAD_BYTES_HOURLY_LIMIT = 512 * 1024 * 1024;
	private static readonly UPLOAD_BYTES_DAILY_LIMIT = 2 * 1024 * 1024 * 1024;
	private static readonly INVALID_UPLOAD_STRIKE_WINDOW_MS = 24 * 60 * 60 * 1000;
	private static readonly INVALID_UPLOAD_STRIKE_LIMIT = 5;
	private static readonly INVALID_UPLOAD_SUSPENSION_MS = 24 * 60 * 60 * 1000;
	private static readonly PENDING_UPLOAD_TTL_MS = 15 * 60 * 1000;
	private static readonly UPLOAD_ATTEMPT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

	sql: SqlStorage;
	readonly bindings: Env;
	private recentUserSearches: number[] = [];
	private uploadTimestamps: number[] = [];

	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		this.bindings = env;
		try {
			migrateDatabase(this.sql);
		} catch (err) {
			testErrorLog(this.bindings, 'do.user', 'constructor_migration_error', {
				doName: this.ctx.id.name,
				phase: 'constructor',
				method: 'migrateDatabase',
				...errorSummary(err),
			});
			throw err;
		}
		testLog(this.bindings, 'do.user', 'init', { doName: this.ctx.id.name });

		// Reply to heartbeat pings without waking a hibernated object.
		this.ctx.setWebSocketAutoResponse(
			new WebSocketRequestResponsePair(JSON.stringify({ type: 'ping' }), JSON.stringify({ type: 'pong' }))
		);

		// Schedule periodic cleanup via alarm (much cheaper than per-wake cleanup).
		this.ctx.waitUntil(this.ensureCleanupAlarm());
	}

	// ── WebSocket lifecycle (Hibernation API) ──

	async fetch(request: Request): Promise<Response> {
		const url = new URL(request.url);
		const token = url.searchParams.get('token');
		testLog(this.bindings, 'do.user', 'ws_fetch_attempt', {
			doName: this.ctx.id.name,
			hasToken: Boolean(token),
		});

		if (!token) {
			testLog(this.bindings, 'do.user', 'ws_fetch_missing_token', { status: 400 });
			return new Response('Missing token', { status: 400 });
		}

		// Validate session token
		const session = this.sql
			.exec("SELECT token FROM sessions WHERE token = ? AND expires_at > datetime('now')", token)
			.toArray();

		if (session.length === 0) {
			testLog(this.bindings, 'do.user', 'ws_fetch_invalid_session', { status: 401 });
			return new Response('Invalid or expired session', { status: 401 });
		}

		// Update last used
		this.sql.exec("UPDATE sessions SET last_used_at = datetime('now') WHERE token = ?", token);

		// Create WebSocket pair
		const pair = new WebSocketPair();
		const [client, server] = Object.values(pair);

		this.ctx.acceptWebSocket(server);

		// Hydrate the client with current preferences immediately on connect.
		this.pushPreferences(server);
		// Push unviewed snaps on connect
		this.pushUnviewedSnaps(server);
		// Push recent chat contacts on connect.
		this.pushChatRecents(server);
		testLog(this.bindings, 'do.user', 'ws_fetch_accepted', { status: 101 });

		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
		if (typeof message !== 'string') {
			testLog(this.bindings, 'do.user', 'ws_message_ignored_non_string');
			return;
		}

		try {
			const msg: ClientMessage = JSON.parse(message);
			testLog(this.bindings, 'do.user', 'ws_message_received', { type: msg.type });
			await handleClientMessage(this, ws, msg);
		} catch (err) {
			testLog(this.bindings, 'do.user', 'ws_message_error', errorSummary(err));
			ws.send(JSON.stringify({ type: 'error', code: 'invalid_message', message: 'Failed to process message' }));
		}
	}

	async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
		testLog(this.bindings, 'do.user', 'ws_close', {
			code,
			wasClean,
			hasReason: reason.length > 0,
		});
		ws.close(code, reason);
	}

	async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
		testLog(this.bindings, 'do.user', 'ws_error', errorSummary(error));
		ws.close(1011, 'Unexpected error');
	}

	// ── RPC methods (called by edge Worker or other DOs) ──

	async createSession(token: string, username: string, skinUrl: string | null): Promise<void> {
		const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
		testLog(this.bindings, 'do.user', 'create_session_start', {
			doName: this.ctx.id.name,
			method: 'createSession',
			username,
			hasSkin: skinUrl !== null,
		});
		try {
			this.sql.exec('INSERT OR REPLACE INTO sessions (token, expires_at) VALUES (?, ?)', token, expiresAt);
			await this.upsertProfile(username, skinUrl);
			testLog(this.bindings, 'do.user', 'session_created', {
				doName: this.ctx.id.name,
				method: 'createSession',
				username,
				hasSkin: skinUrl !== null,
			});
		} catch (err) {
			testErrorLog(this.bindings, 'do.user', 'create_session_error', {
				doName: this.ctx.id.name,
				method: 'createSession',
				username,
				hasSkin: skinUrl !== null,
				...errorSummary(err),
			});
			throw err;
		}
	}

	async getPublicProfile(): Promise<{ username: string; skin_url: string | null } | null> {
		const rows = this.sql.exec('SELECT username, skin_url FROM profile LIMIT 1').toArray();
		if (rows.length === 0) return null;
		return { username: rows[0].username as string, skin_url: (rows[0].skin_url as string) || null };
	}

	getPreferencesSnapshot(): Record<PreferenceKey, number> {
		const preferences: Record<PreferenceKey, number> = { ...DEFAULT_PREFERENCES };
		const rows = this.sql.exec('SELECT key, value FROM preferences').toArray();

		for (const row of rows) {
			const key = typeof row.key === 'string' ? row.key : null;
			if (key && key in DEFAULT_PREFERENCES) {
				preferences[key as PreferenceKey] = Number(row.value);
			}
		}

		preferences.default_expiry_ms = DEFAULT_PREFERENCES.default_expiry_ms;
		return preferences;
	}

	isAcceptedFriend(uuid: string): boolean {
		const normalizedUuid = normalizeUuid(uuid);
		if (!normalizedUuid) {
			return false;
		}
		return this.sql
			.exec("SELECT 1 FROM friends WHERE friend_uuid = ? AND status = 'accepted'", normalizedUuid)
			.toArray().length > 0;
	}

	async getSendSnapTarget(fromUuid: string): Promise<SnapSendTargetResult> {
		const requesterUuid = normalizeUuid(fromUuid);
		if (!this.isAcceptedFriend(requesterUuid)) {
			return { status: 'not_friends' };
		}

		const profile = await this.getPublicProfile();
		if (!profile) return { status: 'user_not_found' };

		return {
			status: 'ready',
			username: profile.username,
			skin_url: profile.skin_url,
		};
	}

	async receiveSnap(snap: SnapDelivery): Promise<'delivered' | 'rejected'> {
		const senderUuid = normalizeUuid(snap.from_uuid);
		const recipientUuid = normalizeUuid(this.ctx.id.name!);
		if (senderUuid === recipientUuid) {
			testLog(this.bindings, 'do.user', 'receive_snap_rejected_self', { snapId: snap.snap_id });
			return 'rejected';
		}
		testLog(this.bindings, 'do.user', 'receive_snap_attempt', {
			snapId: snap.snap_id,
			senderUuid,
		});
		if (!this.isAcceptedFriend(senderUuid)) {
			testLog(this.bindings, 'do.user', 'receive_snap_rejected_not_friend', { snapId: snap.snap_id, senderUuid });
			return 'rejected';
		}

		const expiryMs = Number.isFinite(Number(snap.expiry_ms))
			? Math.max(1_000, Math.floor(Number(snap.expiry_ms)))
			: DEFAULT_PREFERENCES.default_expiry_ms;
		const captionOffsetY = Number.isFinite(Number(snap.caption_offset_y))
			? Math.max(-1, Math.min(1, Number(snap.caption_offset_y)))
			: 0;
		const mediaWidth = BlockChatUserDurableObject.normalizeMediaDimension(snap.media_width);
		const mediaHeight = BlockChatUserDurableObject.normalizeMediaDimension(snap.media_height);
		const expiresAt = typeof snap.expires_at === 'string' && snap.expires_at.length > 0
			? snap.expires_at
			: new Date(Date.parse(snap.sent_at) + expiryMs).toISOString();
		this.sql.exec(
			`INSERT OR REPLACE INTO snap_metadata (
				upload_id, from_uuid, media_type, content_type, media_size,
				media_width, media_height, caption_text, caption_offset_y, expiry_ms, sent_at, expires_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			snap.upload_id,
			senderUuid,
			snap.media_type,
			typeof snap.content_type === 'string' ? snap.content_type : '',
			Number.isFinite(Number(snap.media_size)) ? Math.max(0, Math.floor(Number(snap.media_size))) : 0,
			mediaWidth,
			mediaHeight,
			typeof snap.caption_text === 'string' ? snap.caption_text : '',
			captionOffsetY,
			expiryMs,
			snap.sent_at,
			expiresAt
		);

		this.sql.exec(
			`INSERT INTO received_snaps (snap_id, upload_id, from_uuid, from_username, from_skin_url, media_type, r2_key, sent_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			snap.snap_id,
			snap.upload_id,
			senderUuid,
			snap.from_username,
			snap.from_skin_url,
			snap.media_type,
			snap.r2_key,
			snap.sent_at
		);

		const metadata = this.readSnapMetadata(snap.upload_id);

		// Push to active WebSocket if connected
		const sockets = this.ctx.getWebSockets();
		const notification: ServerMessage = {
			type: 'snap_received',
			snap_id: snap.snap_id,
			upload_id: snap.upload_id,
			from: senderUuid,
			from_username: snap.from_username,
			from_skin_url: snap.from_skin_url,
			media_type: snap.media_type,
			content_type: metadata?.content_type ?? '',
			media_width: metadata?.media_width ?? 0,
			media_height: metadata?.media_height ?? 0,
			caption_text: metadata?.caption_text ?? '',
			caption_offset_y: metadata?.caption_offset_y ?? 0,
			expiry_ms: metadata?.expiry_ms ?? DEFAULT_PREFERENCES.default_expiry_ms,
			sent_at: snap.sent_at,
		};

		for (const ws of sockets) {
			ws.send(JSON.stringify(notification));
		}
		testLog(this.bindings, 'do.user', 'receive_snap_delivered', { snapId: snap.snap_id, socketCount: sockets.length });

		return 'delivered';
	}

	async markUploadExpired(uploadId: string): Promise<void> {
		this.reconcileExpiredUpload(uploadId);
	}

	async snapOpened(uploadId: string, byUuid: string): Promise<void> {
		const normalizedUploadId = typeof uploadId === 'string' ? uploadId.trim() : '';
		if (!normalizedUploadId) {
			return;
		}
		const viewerUuid = normalizeUuid(byUuid);
		this.sql.exec(
			"UPDATE sent_snaps SET opened_at = datetime('now') WHERE upload_id = ? AND to_uuid = ? AND opened_at IS NULL",
			normalizedUploadId,
			viewerUuid
		);

		const sockets = this.ctx.getWebSockets();
		const notification: ServerMessage = { type: 'snap_opened', snap_id: normalizedUploadId, by: viewerUuid };

		for (const ws of sockets) {
			ws.send(JSON.stringify(notification));
		}

		await this.maybeScheduleSnapDeletion(normalizedUploadId);
	}

	/**
	 * Combined check-and-insert for friend requests (replaces the former
	 * two-RPC sequence of getFriendRequestTarget + friendRequest). This
	 * halves the billed request count per friend-add operation.
	 */
	async tryFriendRequest(
		fromUuid: string,
		fromUsername: string,
		fromSkinUrl: string | null
	): Promise<TryFriendRequestResult> {
		const profile = await this.ensurePublicProfile();
		if (!profile) {
			testLog(this.bindings, 'do.user', 'try_friend_request_missing_profile');
			return { status: 'user_not_found' };
		}

		if (!this.isFriendRequestsEnabled()) {
			testLog(this.bindings, 'do.user', 'try_friend_request_disabled', { fromUuid: normalizeUuid(fromUuid) });
			return { status: 'disabled' };
		}

		const requesterUuid = normalizeUuid(fromUuid);
		const existing = this.sql
			.exec('SELECT status FROM friends WHERE friend_uuid = ?', requesterUuid)
			.toArray();
		if (existing.length > 0) {
			testLog(this.bindings, 'do.user', 'try_friend_request_already_exists', { fromUuid: requesterUuid });
			return { status: 'already_exists', username: profile.username, skin_url: profile.skin_url };
		}

		this.sql.exec(
			`INSERT INTO friends (friend_uuid, friend_username, friend_skin_url, status)
			 VALUES (?, ?, ?, 'pending_incoming')`,
			requesterUuid,
			fromUsername,
			fromSkinUrl
		);

		const sockets = this.ctx.getWebSockets();
		const notification: ServerMessage = {
			type: 'friend_request',
			from: requesterUuid,
			from_username: fromUsername,
			from_skin_url: fromSkinUrl,
		};

		for (const ws of sockets) {
			ws.send(JSON.stringify(notification));
		}
		testLog(this.bindings, 'do.user', 'try_friend_request_sent', {
			fromUuid: requesterUuid,
			socketCount: sockets.length,
		});

		return { status: 'sent', username: profile.username, skin_url: profile.skin_url };
	}

	async friendAccepted(byUuid: string, byUsername: string, bySkinUrl: string | null): Promise<void> {
		const normalizedUuid = normalizeUuid(byUuid);
		this.sql.exec(
			"UPDATE friends SET status = 'accepted', friend_username = ?, friend_skin_url = ? WHERE friend_uuid = ?",
			byUsername,
			bySkinUrl,
			normalizedUuid
		);

		const sockets = this.ctx.getWebSockets();
		const notification: ServerMessage = { type: 'friend_added', uuid: normalizedUuid, username: byUsername };

		for (const ws of sockets) {
			ws.send(JSON.stringify(notification));
		}
		testLog(this.bindings, 'do.user', 'friend_accepted', {
			uuid: normalizedUuid,
			socketCount: sockets.length,
		});
	}

	async friendRemoved(byUuid: string): Promise<void> {
		const normalizedUuid = normalizeUuid(byUuid);
		this.sql.exec('DELETE FROM friends WHERE friend_uuid = ?', normalizedUuid);

		const sockets = this.ctx.getWebSockets();
		const notification: ServerMessage = { type: 'friend_removed', uuid: normalizedUuid };

		for (const ws of sockets) {
			ws.send(JSON.stringify(notification));
		}
		testLog(this.bindings, 'do.user', 'friend_removed', {
			uuid: normalizedUuid,
			socketCount: sockets.length,
		});
	}

	async purgePeerAccount(deletedUuid: string): Promise<void> {
		const normalizedDeletedUuid = normalizeUuid(deletedUuid);
		const removedReceivedUploadIds = this.sql
			.exec('SELECT upload_id FROM received_snaps WHERE from_uuid = ?', normalizedDeletedUuid)
			.toArray()
			.map((row) => String(row.upload_id ?? ''))
			.filter((uploadId) => uploadId.length > 0);
		const removedSentUploads = this.sql
			.exec('SELECT upload_id, r2_key FROM sent_snaps WHERE to_uuid = ?', normalizedDeletedUuid)
			.toArray();
		const orphanedSenderUploads = new Map<string, string>();
		for (const row of removedSentUploads) {
			const uploadId = String(row.upload_id ?? '');
			const r2Key = String(row.r2_key ?? '');
			if (uploadId.length > 0 && r2Key.length > 0 && !orphanedSenderUploads.has(uploadId)) {
				orphanedSenderUploads.set(uploadId, r2Key);
			}
		}

		this.sql.exec('DELETE FROM friends WHERE friend_uuid = ?', normalizedDeletedUuid);
		this.sql.exec('DELETE FROM received_snaps WHERE from_uuid = ?', normalizedDeletedUuid);
		this.sql.exec('DELETE FROM sent_snaps WHERE to_uuid = ?', normalizedDeletedUuid);

		await this.deleteUnreferencedUploadArtifacts(
			[...removedReceivedUploadIds, ...orphanedSenderUploads.keys()],
			orphanedSenderUploads
		);
		this.pushLiveStateToActiveSockets();

		testLog(this.bindings, 'do.user', 'purge_peer_account_complete', {
			deletedUuid: normalizedDeletedUuid,
			removedReceivedSnaps: removedReceivedUploadIds.length,
			removedSentSnaps: removedSentUploads.length,
			activeSockets: this.ctx.getWebSockets().length,
		});
	}

	async deleteAccount(): Promise<void> {
		const deletedUuid = normalizeUuid(this.ctx.id.name!);
		const affectedPeerUuids = [
			...new Set([
				...this.sql
					.exec('SELECT friend_uuid FROM friends')
					.toArray()
					.map((row) => normalizeUuid(row.friend_uuid as string)),
				...this.sql
					.exec('SELECT from_uuid FROM received_snaps')
					.toArray()
					.map((row) => normalizeUuid(row.from_uuid as string)),
				...this.sql
					.exec('SELECT to_uuid FROM sent_snaps')
					.toArray()
					.map((row) => normalizeUuid(row.to_uuid as string)),
			]),
		].filter((uuid) => uuid.length > 0 && uuid !== deletedUuid);
		const ownedUploads = new Map<string, string>();
		for (const row of this.sql.exec('SELECT upload_id, r2_key FROM sent_snaps').toArray()) {
			const uploadId = String(row.upload_id ?? '');
			const r2Key = String(row.r2_key ?? '');
			if (uploadId.length > 0 && r2Key.length > 0 && !ownedUploads.has(uploadId)) {
				ownedUploads.set(uploadId, r2Key);
			}
		}

		testLog(this.bindings, 'do.user', 'delete_account_start', {
			deletedUuid,
			affectedPeers: affectedPeerUuids.length,
			ownedUploads: ownedUploads.size,
		});

		for (const peerUuid of affectedPeerUuids) {
			try {
				const peerId = this.bindings.USER_DO.idFromName(peerUuid);
				const peerStub = this.bindings.USER_DO.get(peerId);
				await peerStub.purgePeerAccount(deletedUuid);
			} catch (err) {
				testLog(this.bindings, 'do.user', 'delete_account_peer_purge_error', {
					deletedUuid,
					peerUuid,
					...errorSummary(err),
				});
			}
		}

		for (const [uploadId, r2Key] of ownedUploads) {
			try {
				await this.env.SNAPS_BUCKET.delete(r2Key);
			} catch (err) {
				testLog(this.bindings, 'do.user', 'delete_account_r2_delete_error', {
					deletedUuid,
					uploadId,
					r2Key,
					...errorSummary(err),
				});
			}
		}

		const sockets = this.ctx.getWebSockets();
		for (const ws of sockets) {
			try {
				ws.send(JSON.stringify({ type: 'account_deleted' } satisfies ServerMessage));
			} catch (err) {
				testLog(this.bindings, 'do.user', 'delete_account_socket_notify_error', {
					deletedUuid,
					...errorSummary(err),
				});
			}
		}
		for (const ws of sockets) {
			try {
				ws.close(1000, 'Account deleted');
			} catch {
				// Ignore close failures during teardown.
			}
		}

		this.recentUserSearches = [];
		this.uploadTimestamps = [];
		await this.ctx.storage.deleteAlarm();
		await this.ctx.storage.deleteAll();
		migrateDatabase(this.sql);

		testLog(this.bindings, 'do.user', 'delete_account_complete', {
			deletedUuid,
			closedSockets: sockets.length,
		});
	}

	async resetForTest(): Promise<void> {
		if (String(this.bindings.TEST_MODE ?? '') !== '1') {
			throw new Error('reset_forbidden');
		}

		const sockets = this.ctx.getWebSockets();
		for (const ws of sockets) {
			try {
				ws.close(1012, 'test reset');
			} catch {
				// Ignore close errors; this object is being reset.
			}
		}

		await this.ctx.storage.deleteAlarm();
		await this.ctx.storage.deleteAll();
		this.recentUserSearches = [];
		this.uploadTimestamps = [];
		migrateDatabase(this.sql);

		testLog(this.bindings, 'do.user', 'reset_for_test_complete', {
			doName: this.ctx.id.name,
			closedSockets: sockets.length,
		});
	}

	consumeUserSearchSlot(now = Date.now()): boolean {
		const earliest = now - BlockChatUserDurableObject.USER_SEARCH_WINDOW_MS;
		this.recentUserSearches = this.recentUserSearches.filter((timestamp) => timestamp > earliest);
		if (this.recentUserSearches.length >= BlockChatUserDurableObject.USER_SEARCH_LIMIT) {
			return false;
		}
		this.recentUserSearches.push(now);
		return true;
	}

	/** Reserve an upload slot in the rolling-minute window. */
	consumeUploadSlot(now = Date.now()): boolean {
		this.pruneExpiredPendingUploads(now);
		const earliest = now - BlockChatUserDurableObject.UPLOAD_WINDOW_MS;
		const uploadCount = this.getActiveUploadAttempts(now).filter((attempt) => {
			const createdAtMs = parseTimestampMs(attempt.created_at);
			return Number.isFinite(createdAtMs) && createdAtMs > earliest;
		}).length;
		return uploadCount < BlockChatUserDurableObject.UPLOAD_LIMIT;
	}

	/** Upload admission is now tracked durably; no in-memory release is needed. */
	releaseUploadSlot(): void {
		// No-op. Pending upload reservations are stored in SQLite.
	}

	getUploadAdmissionFailure(reservedSize: number, now = Date.now()): UploadAdmissionFailure | null {
		this.pruneExpiredPendingUploads(now);
		const nowIso = new Date(now).toISOString();
		const suspendedUntil = this.readSuspendedUntilMs();
		if (Number.isFinite(suspendedUntil) && suspendedUntil > now) {
			return {
				code: 'upload_suspended',
				message: `Uploads are temporarily suspended until ${new Date(suspendedUntil).toISOString()}`,
			};
		}

		const activeAttempts = this.getActiveUploadAttempts(now);
		const pendingCount = activeAttempts.filter((attempt) => attempt.status === 'pending').length;
		if (pendingCount >= BlockChatUserDurableObject.PENDING_UPLOAD_LIMIT) {
			return {
				code: 'too_many_pending_uploads',
				message: 'Finish or abandon existing uploads before starting more.',
			};
		}

		const hourlyStart = now - (60 * 60 * 1000);
		const dailyStart = now - (24 * 60 * 60 * 1000);
		let hourlyBytes = 0;
		let dailyBytes = 0;
		for (const attempt of activeAttempts) {
			const createdAtMs = parseTimestampMs(attempt.created_at);
			if (!Number.isFinite(createdAtMs)) {
				continue;
			}
			const accountedBytes = attempt.actual_size ?? attempt.reserved_size;
			if (!Number.isFinite(accountedBytes) || accountedBytes <= 0) {
				continue;
			}
			if (createdAtMs > hourlyStart) {
				hourlyBytes += accountedBytes;
			}
			if (createdAtMs > dailyStart) {
				dailyBytes += accountedBytes;
			}
		}

		if (hourlyBytes + reservedSize > BlockChatUserDurableObject.UPLOAD_BYTES_HOURLY_LIMIT) {
			return {
				code: 'upload_hourly_bytes_limited',
				message: 'Hourly upload byte limit exceeded. Please try again later.',
			};
		}
		if (dailyBytes + reservedSize > BlockChatUserDurableObject.UPLOAD_BYTES_DAILY_LIMIT) {
			return {
				code: 'upload_daily_bytes_limited',
				message: 'Daily upload byte limit exceeded. Please try again later.',
			};
		}

		const invalidSince = now - BlockChatUserDurableObject.INVALID_UPLOAD_STRIKE_WINDOW_MS;
		const invalidStrikes = this.listUploadAttempts().filter((attempt) => {
			if (attempt.status !== 'invalid') {
				return false;
			}
			const updatedAtMs = parseTimestampMs(attempt.updated_at);
			return Number.isFinite(updatedAtMs) && updatedAtMs > invalidSince;
		}).length;
		if (invalidStrikes >= BlockChatUserDurableObject.INVALID_UPLOAD_STRIKE_LIMIT) {
			const suspendedUntilIso = new Date(now + BlockChatUserDurableObject.INVALID_UPLOAD_SUSPENSION_MS).toISOString();
			this.sql.exec(
				`UPDATE abuse_controls
				 SET suspended_until = ?, last_reason = ?, updated_at = ?
				 WHERE id = 1`,
				suspendedUntilIso,
				'invalid_uploads',
				nowIso
			);
			return {
				code: 'upload_suspended',
				message: `Uploads are temporarily suspended until ${suspendedUntilIso}`,
			};
		}

		return null;
	}

	recordPendingUpload(
		uploadId: string,
		r2Key: string,
		hintMediaType: 'image' | 'video',
		hintContentType: string,
		reservedSize: number,
		now = Date.now()
	): void {
		const nowIso = new Date(now).toISOString();
		const expiresAtIso = new Date(now + BlockChatUserDurableObject.PENDING_UPLOAD_TTL_MS).toISOString();
		this.sql.exec(
			`INSERT OR REPLACE INTO upload_attempts (
				upload_id,
				r2_key,
				hint_media_type,
				hint_content_type,
				reserved_size,
				status,
				last_error,
				created_at,
				updated_at,
				expires_at
			)
			VALUES (?, ?, ?, ?, ?, 'pending', '', ?, ?, ?)`,
			uploadId,
			r2Key,
			hintMediaType,
			hintContentType,
			reservedSize,
			nowIso,
			nowIso,
			expiresAtIso
		);
	}

	abandonPendingUpload(uploadId: string, now = Date.now()): void {
		const normalizedUploadId = typeof uploadId === 'string' ? uploadId.trim() : '';
		if (!normalizedUploadId) {
			return;
		}
		const nowIso = new Date(now).toISOString();
		this.sql.exec(
			`UPDATE upload_attempts
			 SET status = 'abandoned', updated_at = ?, expires_at = ?
			 WHERE upload_id = ? AND status = 'pending'`,
			nowIso,
			nowIso,
			normalizedUploadId
		);
		this.sql.exec('DELETE FROM sent_snaps WHERE upload_id = ?', normalizedUploadId);
		this.sql.exec('DELETE FROM snap_metadata WHERE upload_id = ?', normalizedUploadId);
	}

	markUploadValidated(
		uploadId: string,
		actualMediaType: 'image' | 'video',
		actualContentType: string,
		actualSize: number,
		mediaWidth: number,
		mediaHeight: number,
		now = Date.now()
	): void {
		const nowIso = new Date(now).toISOString();
		this.sql.exec(
			`UPDATE upload_attempts
			 SET status = 'validated',
			     actual_media_type = ?,
			     actual_content_type = ?,
			     actual_size = ?,
			     media_width = ?,
			     media_height = ?,
			     last_error = '',
			     updated_at = ?,
			     expires_at = ?
			 WHERE upload_id = ?`,
			actualMediaType,
			actualContentType,
			actualSize,
			mediaWidth,
			mediaHeight,
			nowIso,
			nowIso,
			uploadId
		);
	}

	recordInvalidUpload(uploadId: string, reason: string, actualSize: number | null = null, now = Date.now()): void {
		const normalizedUploadId = typeof uploadId === 'string' ? uploadId.trim() : '';
		if (!normalizedUploadId) {
			return;
		}
		const nowIso = new Date(now).toISOString();
		this.sql.exec(
			`UPDATE upload_attempts
			 SET status = 'invalid',
			     actual_size = COALESCE(?, actual_size),
			     last_error = ?,
			     updated_at = ?,
			     expires_at = ?
			 WHERE upload_id = ?`,
			actualSize,
			reason,
			nowIso,
			nowIso,
			normalizedUploadId
		);

		const strikeWindowStart = now - BlockChatUserDurableObject.INVALID_UPLOAD_STRIKE_WINDOW_MS;
		const invalidStrikeCount = this.listUploadAttempts().filter((attempt) => {
			if (attempt.status !== 'invalid') {
				return false;
			}
			const updatedAtMs = parseTimestampMs(attempt.updated_at);
			return Number.isFinite(updatedAtMs) && updatedAtMs > strikeWindowStart;
		}).length;
		if (invalidStrikeCount >= BlockChatUserDurableObject.INVALID_UPLOAD_STRIKE_LIMIT) {
			const suspendedUntilIso = new Date(now + BlockChatUserDurableObject.INVALID_UPLOAD_SUSPENSION_MS).toISOString();
			this.sql.exec(
				`UPDATE abuse_controls
				 SET suspended_until = ?, last_reason = ?, updated_at = ?
				 WHERE id = 1`,
				suspendedUntilIso,
				'invalid_uploads',
				nowIso
			);
		}
	}

	// ── Alarm-based cleanup ──

	async alarm(): Promise<void> {
		testLog(this.bindings, 'do.user', 'alarm_cleanup_start');
		await this.processPendingSnapDeletes();
		await this.cleanup();

		await this.rescheduleAlarm();
	}

	async scheduleSnapDeletion(uploadId: string): Promise<void> {
		const normalizedUploadId = typeof uploadId === 'string' ? uploadId.trim() : '';
		if (!normalizedUploadId) {
			return;
		}

		const upload = this.sql
			.exec('SELECT r2_key FROM sent_snaps WHERE upload_id = ? LIMIT 1', normalizedUploadId)
			.toArray();
		if (upload.length === 0) {
			testLog(this.bindings, 'do.user', 'schedule_snap_delete_missing_upload', { uploadId: normalizedUploadId });
			return;
		}

		const r2Key = String(upload[0].r2_key);
		try {
			await this.env.SNAPS_BUCKET.delete(r2Key);
			const deadlines = await this.readPendingSnapDeleteDeadlines();
			if (deadlines[normalizedUploadId] != null) {
				delete deadlines[normalizedUploadId];
				await this.writePendingSnapDeleteDeadlines(deadlines);
			}
			testLog(this.bindings, 'do.user', 'snap_delete_r2_deleted_immediately', {
				uploadId: normalizedUploadId,
				r2Key,
			});
		} catch (err) {
			const deadlines = await this.readPendingSnapDeleteDeadlines();
			deadlines[normalizedUploadId] = Date.now();
			await this.writePendingSnapDeleteDeadlines(deadlines);
			await this.rescheduleAlarm();
			testLog(this.bindings, 'do.user', 'snap_delete_r2_delete_failed_retry_scheduled', {
				uploadId: normalizedUploadId,
				r2Key,
				...errorSummary(err),
			});
		}
	}

	// ── Internal helpers ──

	private async ensureCleanupAlarm(): Promise<void> {
		const currentAlarm = await this.ctx.storage.getAlarm();
		if (currentAlarm === null) {
			const nextAlarmAt = await this.computeNextAlarmAt();
			await this.ctx.storage.setAlarm(nextAlarmAt ?? (Date.now() + CLEANUP_INTERVAL_MS));
		}
	}

	private async cleanup(): Promise<void> {
		this.reconcileExpiredSnaps();
		await this.cleanupExpiredPendingUploads();

		// Clean up expired sessions
		this.sql.exec("DELETE FROM sessions WHERE expires_at < datetime('now')");

		// Clean up viewed recipient rows older than 24h. Sender-owned cleanup
		// owns the shared R2 objects so one recipient cannot delete media for others.
		const oldReceivedSnaps = this.sql
			.exec(
				"SELECT snap_id FROM received_snaps WHERE viewed_at IS NOT NULL AND viewed_at < datetime('now', '-1 day')"
			)
			.toArray();
		if (oldReceivedSnaps.length > 0) {
			this.sql.exec(
				"DELETE FROM received_snaps WHERE viewed_at IS NOT NULL AND viewed_at < datetime('now', '-1 day')"
			);
		}

		const oldSentUploads = this.sql
			.exec(
				`SELECT upload_id, r2_key
				 FROM sent_snaps
				 GROUP BY upload_id, r2_key
				 HAVING COUNT(*) = SUM(CASE WHEN opened_at IS NOT NULL OR dropped_at IS NOT NULL THEN 1 ELSE 0 END)
				    AND MAX(COALESCE(opened_at, dropped_at)) < datetime('now', '-1 day')`
			)
			.toArray();

		for (const upload of oldSentUploads) {
			try {
				await this.env.SNAPS_BUCKET.delete(upload.r2_key as string);
			} catch (err) {
				testLog(this.bindings, 'do.user', 'cleanup_delete_r2_error', errorSummary(err));
			}
			this.sql.exec('DELETE FROM sent_snaps WHERE upload_id = ?', upload.upload_id as string);
			this.sql.exec('DELETE FROM snap_metadata WHERE upload_id = ?', upload.upload_id as string);
		}

		const uploadAttemptRetentionCutoff = new Date(
			Date.now() - BlockChatUserDurableObject.UPLOAD_ATTEMPT_RETENTION_MS
		).toISOString();
		this.sql.exec('DELETE FROM upload_attempts WHERE updated_at < ?', uploadAttemptRetentionCutoff);

		testLog(this.bindings, 'do.user', 'cleanup_complete', {
			deletedViewedSnaps: oldReceivedSnaps.length,
			deletedSentUploads: oldSentUploads.length,
		});
	}

	private async cleanupExpiredPendingUploads(now = Date.now()): Promise<void> {
		const expiredAttempts = this.pruneExpiredPendingUploads(now);
		for (const attempt of expiredAttempts) {
			if (!attempt.r2_key) {
				continue;
			}
			try {
				await this.env.SNAPS_BUCKET.delete(attempt.r2_key);
			} catch (err) {
				testLog(this.bindings, 'do.user', 'cleanup_expired_pending_upload_r2_error', {
					uploadId: attempt.upload_id,
					r2Key: attempt.r2_key,
					...errorSummary(err),
				});
			}
		}
	}

	private async processPendingSnapDeletes(): Promise<void> {
		const now = Date.now();
		const deadlines = await this.readPendingSnapDeleteDeadlines();
		const remaining: PendingSnapDeleteDeadlines = {};
		let deletedCount = 0;

		for (const [uploadId, deleteAt] of Object.entries(deadlines)) {
			if (!Number.isFinite(deleteAt) || deleteAt > now) {
				remaining[uploadId] = deleteAt;
				continue;
			}

			const r2KeyRow = this.sql.exec('SELECT r2_key FROM sent_snaps WHERE upload_id = ? LIMIT 1', uploadId).toArray();
			if (r2KeyRow.length === 0) {
				deletedCount++;
				continue;
			}

			const r2Key = String(r2KeyRow[0].r2_key);
			try {
				await this.env.SNAPS_BUCKET.delete(r2Key);
				deletedCount++;
				testLog(this.bindings, 'do.user', 'pending_snap_delete_r2_deleted', { uploadId, r2Key });
			} catch (err) {
				remaining[uploadId] = deleteAt;
				testLog(this.bindings, 'do.user', 'pending_snap_delete_r2_error', {
					uploadId,
					r2Key,
					...errorSummary(err),
				});
			}
		}

		await this.writePendingSnapDeleteDeadlines(remaining);
		testLog(this.bindings, 'do.user', 'pending_snap_deletes_processed', {
			deletedCount,
			remainingCount: Object.keys(remaining).length,
		});
	}

	private async maybeScheduleSnapDeletion(uploadId: string): Promise<void> {
		const normalizedUploadId = typeof uploadId === 'string' ? uploadId.trim() : '';
		if (!normalizedUploadId) {
			return;
		}

		const rows = this.sql
			.exec(
				`SELECT
					SUM(CASE WHEN delivered_at IS NOT NULL THEN 1 ELSE 0 END) AS delivered_count,
					SUM(CASE WHEN delivered_at IS NOT NULL AND opened_at IS NULL AND dropped_at IS NULL THEN 1 ELSE 0 END) AS pending_count
				 FROM sent_snaps
				 WHERE upload_id = ?`,
				normalizedUploadId
			)
			.toArray();
		if (rows.length === 0) {
			return;
		}
		const deliveredCount = Number(rows[0].delivered_count ?? 0);
		const pendingCount = Number(rows[0].pending_count ?? 0);
		if (deliveredCount <= 0 || pendingCount > 0) {
			return;
		}

		await this.scheduleSnapDeletion(normalizedUploadId);
	}

	private async readPendingSnapDeleteDeadlines(): Promise<PendingSnapDeleteDeadlines> {
		const raw = await this.ctx.storage.get(PENDING_SNAP_DELETE_STORAGE_KEY);
		if (!raw || typeof raw !== 'object') {
			return {};
		}

		const deadlines: PendingSnapDeleteDeadlines = {};
		for (const [uploadId, value] of Object.entries(raw as Record<string, unknown>)) {
			const deleteAt = Number(value);
			if (Number.isFinite(deleteAt)) {
				deadlines[uploadId] = deleteAt;
			}
		}

		return deadlines;
	}

	private async writePendingSnapDeleteDeadlines(deadlines: PendingSnapDeleteDeadlines): Promise<void> {
		if (Object.keys(deadlines).length === 0) {
			await this.ctx.storage.delete(PENDING_SNAP_DELETE_STORAGE_KEY);
			return;
		}

		await this.ctx.storage.put(PENDING_SNAP_DELETE_STORAGE_KEY, deadlines);
	}

	private async rescheduleAlarm(): Promise<void> {
		const nextAlarmAt = await this.computeNextAlarmAt();
		if (nextAlarmAt === null) {
			return;
		}

		const currentAlarm = await this.ctx.storage.getAlarm();
		if (currentAlarm === null || nextAlarmAt < currentAlarm) {
			await this.ctx.storage.setAlarm(nextAlarmAt);
			testLog(this.bindings, 'do.user', 'alarm_rescheduled', { alarmAt: nextAlarmAt });
		}
	}

	private async computeNextAlarmAt(): Promise<number | null> {
		const pendingDeadlines = await this.readPendingSnapDeleteDeadlines();
		const deleteDeadlines = Object.values(pendingDeadlines).filter((deadline) => Number.isFinite(deadline));
		const earliestDeleteAt = deleteDeadlines.length > 0 ? Math.min(...deleteDeadlines) : null;

		const hasSessions = this.sql.exec('SELECT 1 FROM sessions LIMIT 1').toArray().length > 0;
		const hasSnaps = this.sql.exec('SELECT 1 FROM received_snaps LIMIT 1').toArray().length > 0;
		const hasSentSnaps = this.sql.exec('SELECT 1 FROM sent_snaps LIMIT 1').toArray().length > 0;
		const needsCleanup = hasSessions || hasSnaps || hasSentSnaps;
		const cleanupAt = needsCleanup ? Date.now() + CLEANUP_INTERVAL_MS : null;

		if (earliestDeleteAt !== null && cleanupAt !== null) {
			return Math.min(earliestDeleteAt, cleanupAt);
		}
		if (earliestDeleteAt !== null) {
			return earliestDeleteAt;
		}
		return cleanupAt;
	}

	private isFriendRequestsEnabled(): boolean {
		return this.readPreference('friend_requests') === PreferenceToggle.On;
	}

	private readPreference(key: PreferenceKey): number {
		const prefRow = this.sql.exec('SELECT value FROM preferences WHERE key = ?', key).toArray();
		const preference = prefRow.length > 0 ? Number(prefRow[0].value) : DEFAULT_PREFERENCES[key];
		return Number.isFinite(preference) ? preference : DEFAULT_PREFERENCES[key];
	}

	private pushUnviewedSnaps(ws: WebSocket): void {
		const snaps = this.getUnreadSnapsSnapshot();

		if (snaps.length === 0) {
			testLog(this.bindings, 'do.user', 'push_unviewed_snaps_none');
			return;
		}

		const msg: ServerMessage = {
			type: 'snap_list',
			snaps,
		};

		ws.send(JSON.stringify(msg));
		testLog(this.bindings, 'do.user', 'push_unviewed_snaps', { count: snaps.length });
	}

	getFriendsSnapshot(): Array<{
		uuid: string;
		username: string;
		skin_url: string | null;
		status: 'pending_outgoing' | 'pending_incoming' | 'accepted';
	}> {
		return this.sql.exec('SELECT friend_uuid, friend_username, friend_skin_url, status FROM friends').toArray().map((row) => ({
			uuid: normalizeUuid(row.friend_uuid as string),
			username: row.friend_username as string,
			skin_url: (row.friend_skin_url as string) || null,
			status: row.status as 'pending_outgoing' | 'pending_incoming' | 'accepted',
		}));
	}

	getUnreadSnapsSnapshot(): SnapInboxEntry[] {
		this.reconcileExpiredSnaps();
		const nowIso = new Date().toISOString();
		const rows = this.sql
			.exec(
		`SELECT
			rs.snap_id,
			rs.upload_id,
			rs.from_uuid,
			rs.from_username,
			rs.from_skin_url,
			rs.media_type,
			rs.sent_at,
			sm.content_type,
			sm.media_width,
			sm.media_height,
			sm.caption_text,
			sm.caption_offset_y,
			sm.expiry_ms
		FROM received_snaps rs
		LEFT JOIN snap_metadata sm ON sm.upload_id = rs.upload_id
				WHERE rs.viewed_at IS NULL
				  AND (sm.expires_at IS NULL OR sm.expires_at > ?)
				ORDER BY rs.from_uuid ASC, rs.sent_at ASC, rs.snap_id ASC`,
				nowIso
			)
			.toArray();

		const grouped = new Map<string, Array<Record<string, unknown>>>();
		for (const row of rows) {
			const uuid = normalizeUuid(row.from_uuid as string);
			const senderRows = grouped.get(uuid);
			if (senderRows) {
				senderRows.push(row);
			} else {
				grouped.set(uuid, [row]);
			}
		}

		const entries: SnapInboxEntry[] = [];
		for (const [fromUuid, senderRows] of grouped) {
			senderRows.sort((a, b) => {
				const sentCompare = String(a.sent_at).localeCompare(String(b.sent_at));
				if (sentCompare !== 0) return sentCompare;
				return String(a.snap_id).localeCompare(String(b.snap_id));
			});
			const oldestSnapId = String(senderRows[0].snap_id);
			for (let i = 0; i < senderRows.length; i++) {
				const row = senderRows[i];
				entries.push({
					snap_id: String(row.snap_id),
					upload_id: String(row.upload_id),
					from: fromUuid,
					from_username: String(row.from_username),
					from_skin_url: (row.from_skin_url as string) ?? null,
					media_type: row.media_type as 'image' | 'video',
					content_type: typeof row.content_type === 'string' ? (row.content_type as string) : '',
					media_width: Number.isFinite(Number(row.media_width)) ? Number(row.media_width) : 0,
					media_height: Number.isFinite(Number(row.media_height)) ? Number(row.media_height) : 0,
					caption_text: typeof row.caption_text === 'string' ? (row.caption_text as string) : '',
					caption_offset_y: Number.isFinite(Number(row.caption_offset_y)) ? Number(row.caption_offset_y) : 0,
					expiry_ms: Number.isFinite(Number(row.expiry_ms))
						? Number(row.expiry_ms)
						: DEFAULT_PREFERENCES.default_expiry_ms,
					sent_at: String(row.sent_at),
					sender_unread_position: i + 1,
					sender_unread_total: senderRows.length,
					sender_oldest_unread_snap_id: oldestSnapId,
					sender_next_unread_snap_id: i + 1 < senderRows.length ? String(senderRows[i + 1].snap_id) : null,
				});
			}
		}

		return entries.sort((a, b) => {
			const sentCompare = a.sent_at.localeCompare(b.sent_at);
			if (sentCompare !== 0) return sentCompare;
			return a.snap_id.localeCompare(b.snap_id);
		});
	}

	getChatRecentsSnapshot(): ChatRecentEntry[] {
		this.reconcileExpiredSnaps();
		const recents = new Map<string, ChatRecentEntry>();
		const outgoingUnopenedByUuid = new Map<string, PendingStatusSummary>();
		const incomingUnopenedByUuid = new Map<string, PendingStatusSummary>();
		const activityByUuid = new Map<string, ActivitySummary>();

		const updateActivity = (uuid: string, type: ActivitySummary['type'], timestamp: string | null): void => {
			if (!timestamp) {
				return;
			}
			const existing = activityByUuid.get(uuid);
			if (!existing || timestamp > existing.timestamp) {
				activityByUuid.set(uuid, { type, timestamp });
			}
		};

		const sentRows = this.sql
			.exec(
				`SELECT to_uuid, to_username, to_skin_url, media_type, sent_at, opened_at, dropped_at
				 FROM sent_snaps
				 ORDER BY sent_at DESC`
			)
			.toArray();
		for (const row of sentRows) {
			const uuid = normalizeUuid(row.to_uuid as string);
			updateActivity(uuid, 'sent', row.sent_at as string);
			updateActivity(uuid, 'opened', (row.opened_at as string) || null);
			updateActivity(uuid, 'dropped', (row.dropped_at as string) || null);
			if (row.opened_at == null && row.dropped_at == null) {
				const existingPending = outgoingUnopenedByUuid.get(uuid);
				outgoingUnopenedByUuid.set(uuid, {
					count: (existingPending?.count ?? 0) + 1,
					media_type: (existingPending?.media_type ?? (row.media_type as 'image' | 'video')),
					timestamp: existingPending?.timestamp ?? (row.sent_at as string),
				});
			}
			if (recents.has(uuid)) {
				continue;
			}
			recents.set(uuid, {
				uuid,
				username: row.to_username as string,
				skin_url: (row.to_skin_url as string) || null,
				last_direction: 'sent',
				last_media_type: row.media_type as 'image' | 'video',
				last_timestamp: row.sent_at as string,
				last_activity_type: 'sent',
				last_activity_timestamp: row.sent_at as string,
				incoming_unopened_count: 0,
				incoming_unopened_media_type: null,
				incoming_unopened_timestamp: null,
				outgoing_unopened_count: 0,
				outgoing_unopened_media_type: null,
				outgoing_unopened_timestamp: null,
			});
		}

		const receivedRows = this.sql
			.exec(
				`SELECT from_uuid, from_username, from_skin_url, media_type, sent_at, viewed_at
				 FROM received_snaps
				 ORDER BY sent_at DESC`
			)
			.toArray();
		for (const row of receivedRows) {
			if (row.viewed_at == null) {
				const uuid = normalizeUuid(row.from_uuid as string);
				const existingPending = incomingUnopenedByUuid.get(uuid);
				incomingUnopenedByUuid.set(uuid, {
					count: (existingPending?.count ?? 0) + 1,
					media_type: (existingPending?.media_type ?? (row.media_type as 'image' | 'video')),
					timestamp: existingPending?.timestamp ?? (row.sent_at as string),
				});
			}
		}
		for (const row of receivedRows) {
			const uuid = normalizeUuid(row.from_uuid as string);
			updateActivity(uuid, 'received', row.sent_at as string);
			const candidate: ChatRecentEntry = {
				uuid,
				username: row.from_username as string,
				skin_url: (row.from_skin_url as string) || null,
				last_direction: 'received',
				last_media_type: row.media_type as 'image' | 'video',
				last_timestamp: row.sent_at as string,
				last_activity_type: 'received',
				last_activity_timestamp: row.sent_at as string,
				incoming_unopened_count: 0,
				incoming_unopened_media_type: null,
				incoming_unopened_timestamp: null,
				outgoing_unopened_count: 0,
				outgoing_unopened_media_type: null,
				outgoing_unopened_timestamp: null,
			};
			const existing = recents.get(uuid);
			if (!existing || candidate.last_timestamp > existing.last_timestamp) {
				recents.set(uuid, candidate);
			}
		}

		for (const [uuid, recent] of recents) {
			const incomingPending = incomingUnopenedByUuid.get(uuid);
			const outgoingPending = outgoingUnopenedByUuid.get(uuid);
			const latestActivity = activityByUuid.get(uuid);
			recents.set(uuid, {
				...recent,
				last_activity_type: latestActivity?.type ?? recent.last_activity_type,
				last_activity_timestamp: latestActivity?.timestamp ?? recent.last_timestamp,
				incoming_unopened_count: incomingPending?.count ?? 0,
				incoming_unopened_media_type: incomingPending?.media_type ?? null,
				incoming_unopened_timestamp: incomingPending?.timestamp ?? null,
				outgoing_unopened_count: outgoingPending?.count ?? 0,
				outgoing_unopened_media_type: outgoingPending?.media_type ?? null,
				outgoing_unopened_timestamp: outgoingPending?.timestamp ?? null,
			});
		}

		return [...recents.values()].sort((a, b) => {
			const activityCompare = b.last_activity_timestamp.localeCompare(a.last_activity_timestamp);
			if (activityCompare !== 0) {
				return activityCompare;
			}
			return b.last_timestamp.localeCompare(a.last_timestamp);
		});
	}

	private pushChatRecents(ws: WebSocket): void {
		const recents = this.getChatRecentsSnapshot();
		ws.send(JSON.stringify({ type: 'chat_recents', recents } satisfies ServerMessage));
		testLog(this.bindings, 'do.user', 'push_chat_recents', { count: recents.length });
	}

	private pushFriendList(ws: WebSocket): void {
		const friends = this.getFriendsSnapshot();
		ws.send(JSON.stringify({ type: 'friend_list', friends } satisfies ServerMessage));
		testLog(this.bindings, 'do.user', 'push_friend_list', { count: friends.length });
	}

	private pushPreferences(ws: WebSocket): void {
		const msg: ServerMessage = {
			type: 'preferences',
			preferences: this.getPreferencesSnapshot(),
		};

		ws.send(JSON.stringify(msg));
		testLog(this.bindings, 'do.user', 'push_preferences');
	}

	private pushLiveStateToActiveSockets(includePreferences = false): void {
		const sockets = this.ctx.getWebSockets();
		for (const ws of sockets) {
			this.pushFriendList(ws);
			ws.send(JSON.stringify({ type: 'snap_list', snaps: this.getUnreadSnapsSnapshot() } satisfies ServerMessage));
			this.pushChatRecents(ws);
			if (includePreferences) {
				this.pushPreferences(ws);
			}
		}
		testLog(this.bindings, 'do.user', 'push_live_state_to_active_sockets', {
			socketCount: sockets.length,
			includePreferences,
		});
	}

	private listUploadAttempts(): UploadAttemptRow[] {
		return this.sql
			.exec(
				`SELECT
					upload_id,
					r2_key,
					hint_media_type,
					hint_content_type,
					reserved_size,
					actual_media_type,
					actual_content_type,
					actual_size,
					media_width,
					media_height,
					status,
					last_error,
					created_at,
					updated_at,
					expires_at
				FROM upload_attempts`
			)
			.toArray()
			.map((row) => ({
				upload_id: String(row.upload_id ?? ''),
				r2_key: String(row.r2_key ?? ''),
				hint_media_type: row.hint_media_type === 'video' ? 'video' : 'image',
				hint_content_type: String(row.hint_content_type ?? ''),
				reserved_size: Number.isFinite(Number(row.reserved_size)) ? Number(row.reserved_size) : 0,
				actual_media_type: row.actual_media_type === 'image' || row.actual_media_type === 'video'
					? (row.actual_media_type as 'image' | 'video')
					: null,
				actual_content_type: typeof row.actual_content_type === 'string' ? (row.actual_content_type as string) : null,
				actual_size: Number.isFinite(Number(row.actual_size)) ? Number(row.actual_size) : null,
				media_width: Number.isFinite(Number(row.media_width)) ? Number(row.media_width) : 0,
				media_height: Number.isFinite(Number(row.media_height)) ? Number(row.media_height) : 0,
				status: this.normalizeUploadAttemptStatus(row.status),
				last_error: String(row.last_error ?? ''),
				created_at: String(row.created_at ?? ''),
				updated_at: String(row.updated_at ?? ''),
				expires_at: String(row.expires_at ?? ''),
			}));
	}

	private normalizeUploadAttemptStatus(value: unknown): UploadAttemptStatus {
		switch (value) {
			case 'pending':
			case 'validated':
			case 'invalid':
			case 'abandoned':
				return value;
			default:
				return 'abandoned';
		}
	}

	private getActiveUploadAttempts(now = Date.now()): UploadAttemptRow[] {
		return this.listUploadAttempts().filter((attempt) => {
			if (attempt.status === 'pending') {
				const expiresAtMs = parseTimestampMs(attempt.expires_at);
				return Number.isFinite(expiresAtMs) && expiresAtMs > now;
			}
			return attempt.status === 'validated' || attempt.status === 'invalid';
		});
	}

	private pruneExpiredPendingUploads(now = Date.now()): UploadAttemptRow[] {
		const nowIso = new Date(now).toISOString();
		const expiredAttempts = this.listUploadAttempts().filter((attempt) => {
			if (attempt.status !== 'pending') {
				return false;
			}
			const expiresAtMs = parseTimestampMs(attempt.expires_at);
			return Number.isFinite(expiresAtMs) && expiresAtMs <= now;
		});
		for (const attempt of expiredAttempts) {
			this.sql.exec(
				`UPDATE upload_attempts
				 SET status = 'abandoned', updated_at = ?, expires_at = ?
				 WHERE upload_id = ?`,
				nowIso,
				nowIso,
				attempt.upload_id
			);
			this.sql.exec('DELETE FROM sent_snaps WHERE upload_id = ?', attempt.upload_id);
			this.sql.exec('DELETE FROM snap_metadata WHERE upload_id = ?', attempt.upload_id);
		}
		return expiredAttempts;
	}

	private readSuspendedUntilMs(): number {
		const row = this.sql
			.exec('SELECT suspended_until FROM abuse_controls WHERE id = 1 LIMIT 1')
			.toArray()[0];
		if (!row || typeof row.suspended_until !== 'string' || row.suspended_until.length === 0) {
			return Number.NaN;
		}
		const suspendedUntilMs = parseTimestampMs(row.suspended_until as string);
		if (Number.isFinite(suspendedUntilMs) && suspendedUntilMs <= Date.now()) {
			this.sql.exec(
				`UPDATE abuse_controls
				 SET suspended_until = ?, last_reason = ?, updated_at = ?
				 WHERE id = 1`,
				null,
				'',
				new Date().toISOString()
			);
			return Number.NaN;
		}
		return suspendedUntilMs;
	}

	private readSnapMetadata(uploadId: string): {
		content_type: string;
		media_width: number;
		media_height: number;
		caption_text: string;
		caption_offset_y: number;
		expiry_ms: number;
	} | null {
		const rows = this.sql
			.exec(
				`SELECT content_type, media_width, media_height, caption_text, caption_offset_y, expiry_ms
				 FROM snap_metadata
				 WHERE upload_id = ?
				 LIMIT 1`,
				uploadId
			)
			.toArray();
		if (rows.length === 0) {
			return null;
		}
		const row = rows[0];
		return {
			content_type: typeof row.content_type === 'string' ? (row.content_type as string) : '',
			media_width: Number.isFinite(Number(row.media_width)) ? Number(row.media_width) : 0,
			media_height: Number.isFinite(Number(row.media_height)) ? Number(row.media_height) : 0,
			caption_text: typeof row.caption_text === 'string' ? (row.caption_text as string) : '',
			caption_offset_y: Number.isFinite(Number(row.caption_offset_y)) ? Number(row.caption_offset_y) : 0,
			expiry_ms: Number.isFinite(Number(row.expiry_ms))
				? Number(row.expiry_ms)
				: DEFAULT_PREFERENCES.default_expiry_ms,
		};
	}

	private reconcileExpiredSnaps(): void {
		const nowIso = new Date().toISOString();
		const expiredUploads = this.sql
			.exec('SELECT upload_id FROM snap_metadata WHERE expires_at <= ?', nowIso)
			.toArray();
		if (expiredUploads.length === 0) {
			return;
		}
		for (const row of expiredUploads) {
			const uploadId = typeof row.upload_id === 'string' ? row.upload_id : '';
			if (!uploadId) {
				continue;
			}
			this.reconcileExpiredUpload(uploadId);
		}
	}

	private reconcileExpiredUpload(uploadId: string): void {
		this.sql.exec(
			"UPDATE received_snaps SET viewed_at = datetime('now') WHERE upload_id = ? AND viewed_at IS NULL",
			uploadId
		);
		this.sql.exec(
			"UPDATE sent_snaps SET dropped_at = datetime('now') WHERE upload_id = ? AND opened_at IS NULL AND dropped_at IS NULL",
			uploadId
		);
	}

	private async deleteUnreferencedUploadArtifacts(
		uploadIds: Iterable<string>,
		orphanedSenderUploads = new Map<string, string>()
	): Promise<void> {
		for (const uploadId of new Set([...uploadIds].filter((value) => value.length > 0))) {
			const hasReceivedRows = this.sql.exec('SELECT 1 FROM received_snaps WHERE upload_id = ? LIMIT 1', uploadId).toArray().length > 0;
			const hasSentRows = this.sql.exec('SELECT 1 FROM sent_snaps WHERE upload_id = ? LIMIT 1', uploadId).toArray().length > 0;
			if (hasReceivedRows || hasSentRows) {
				continue;
			}

			const r2Key = orphanedSenderUploads.get(uploadId);
			if (r2Key) {
				try {
					await this.env.SNAPS_BUCKET.delete(r2Key);
				} catch (err) {
					testLog(this.bindings, 'do.user', 'delete_unreferenced_r2_error', {
						uploadId,
						r2Key,
						...errorSummary(err),
					});
				}
			}

			this.sql.exec('DELETE FROM snap_metadata WHERE upload_id = ?', uploadId);
		}
	}

	private async ensurePublicProfile(): Promise<{ username: string; skin_url: string | null } | null> {
		const existing = this.sql.exec('SELECT username, skin_url FROM profile LIMIT 1').toArray();
		if (existing.length > 0) {
			return {
				username: existing[0].username as string,
				skin_url: (existing[0].skin_url as string) || null,
			};
		}

		const profileUuid = normalizeUuid(this.ctx.id.name!);
		try {
			const profile = await fetchMojangPublicProfileByUuid(profileUuid);
			await this.upsertProfile(profile.username, profile.skinUrl);
			testLog(this.bindings, 'do.user', 'profile_bootstrapped', {
				uuid: profileUuid,
				username: profile.username,
				hasSkin: profile.skinUrl !== null,
			});
			return {
				username: profile.username,
				skin_url: profile.skinUrl,
			};
		} catch (err) {
			if (err instanceof AuthError && (err.step === 'unknown_uuid' || err.step === 'invalid_uuid')) {
				testLog(this.bindings, 'do.user', 'profile_bootstrap_missing', { uuid: profileUuid });
				return null;
			}
			throw err;
		}
	}

	private async upsertProfile(username: string, skinUrl: string | null): Promise<void> {
		const profileUuid = normalizeUuid(this.ctx.id.name!);
		testLog(this.bindings, 'do.user', 'upsert_profile_start', {
			doName: this.ctx.id.name,
			method: 'upsertProfile',
			username,
			hasSkin: skinUrl !== null,
		});
		try {
			this.sql.exec(
				`INSERT INTO profile (uuid, username, skin_url, updated_at)
				 VALUES (?, ?, ?, datetime('now'))
				 ON CONFLICT(uuid) DO UPDATE SET
				   username = excluded.username,
				   skin_url = excluded.skin_url,
				   updated_at = datetime('now')`,
				profileUuid,
				username,
				skinUrl
			);
			testLog(this.bindings, 'do.user', 'upsert_profile_success', {
				doName: this.ctx.id.name,
				method: 'upsertProfile',
				username,
				hasSkin: skinUrl !== null,
			});
		} catch (err) {
			testErrorLog(this.bindings, 'do.user', 'upsert_profile_error', {
				doName: this.ctx.id.name,
				method: 'upsertProfile',
				username,
				hasSkin: skinUrl !== null,
				...errorSummary(err),
			});
			throw err;
		}
	}

	private static normalizeMediaDimension(value: unknown): number {
		if (value == null) {
			return 0;
		}
		const numeric = Number(value);
		if (!Number.isFinite(numeric) || numeric < 0) {
			return 0;
		}
		return Math.min(16384, Math.floor(numeric));
	}
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
