import { env, createExecutionContext, waitOnExecutionContext, SELF } from 'cloudflare:test';
import { describe, it, expect, vi, afterEach } from 'vitest';
import worker from '../src/index';
import * as presign from '../src/r2/presign';
import { handleClientMessage } from '../src/do/messages';
import { BlockChatUserDurableObject } from '../src/do/user-do';
import * as helpers from '../src/helpers';
import { fetchMojangPublicProfile, fetchMojangPublicProfileByUuid } from '../src/helpers';

// For now, you'll need to do something like this to get a correctly-typed
// `Request` to pass to `worker.fetch()`.
const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;

const SENDER_UUID = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const RECIPIENT_UUID = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';
const RECIPIENT_TWO_UUID = 'cccccccccccccccccccccccccccccccc';
const BOOTSTRAPPED_USERNAME = 'BootstrapTarget';
const BOOTSTRAPPED_SKIN = 'https://textures.minecraft.net/skin/bootstrap';

type ProfileRow = {
	uuid: string;
	username: string;
	skin_url: string | null;
};

function makeMinimalPng(width: number, height: number): Uint8Array {
	return Uint8Array.from([
		0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
		0x00, 0x00, 0x00, 0x0d,
		0x49, 0x48, 0x44, 0x52,
		(width >>> 24) & 0xff, (width >>> 16) & 0xff, (width >>> 8) & 0xff, width & 0xff,
		(height >>> 24) & 0xff, (height >>> 16) & 0xff, (height >>> 8) & 0xff, height & 0xff,
		0x08, 0x02, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x49, 0x45, 0x4e, 0x44,
		0xae, 0x42, 0x60, 0x82,
	]);
}

function makeMinimalJpeg(width: number, height: number): Uint8Array {
	return Uint8Array.from([
		0xff, 0xd8,
		0xff, 0xe0, 0x00, 0x10,
		0x4a, 0x46, 0x49, 0x46, 0x00,
		0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
		0xff, 0xc0, 0x00, 0x11,
		0x08,
		(height >>> 8) & 0xff, height & 0xff,
		(width >>> 8) & 0xff, width & 0xff,
		0x03,
		0x01, 0x11, 0x00,
		0x02, 0x11, 0x00,
		0x03, 0x11, 0x00,
		0xff, 0xd9,
	]);
}

function createMockR2Bucket(bytes: Uint8Array, contentType: string) {
	let storedContentType = contentType;
	const putMock = vi.fn(async (_key: string, _value: unknown, options?: R2PutOptions) => {
		storedContentType = options?.httpMetadata?.contentType ?? storedContentType;
		return {
			size: bytes.byteLength,
			httpMetadata: { contentType: storedContentType },
		} as unknown as R2Object;
	});
	const getMock = vi.fn(async (_key: string, options?: R2GetOptions) => {
		if (options?.range && 'offset' in options.range) {
			const offset = options.range.offset ?? 0;
			const length = options.range.length ?? (bytes.byteLength - offset);
			const chunk = bytes.subarray(offset, offset + length);
			return {
				bytes: async () => chunk,
			} as unknown as R2ObjectBody;
		}

		return {
			body: new Blob([bytes]).stream(),
			httpMetadata: { contentType: storedContentType },
			customMetadata: {},
		} as unknown as R2ObjectBody;
	});

	return {
		bucket: {
			head: vi.fn(async () => ({
				size: bytes.byteLength,
				httpMetadata: { contentType: storedContentType },
			})),
			get: getMock,
			put: putMock,
			delete: vi.fn(async () => undefined),
		} as unknown as R2Bucket,
		getStoredContentType: () => storedContentType,
		putMock,
	};
}

function acceptedFriend(friend_uuid: string, friend_username: string, friend_skin_url: string | null = null) {
	return {
		friend_uuid,
		friend_username,
		friend_skin_url,
		status: 'accepted',
	};
}

function rowResult<T extends Record<string, unknown>>(rows: T[]) {
	return {
		toArray: () => rows,
		one: () => {
			if (rows.length === 0) {
				throw new Error('Expected one row');
			}
			return rows[0];
		},
	};
}

function createStatefulUserDo(options: {
	uuid: string;
	profile?: ProfileRow | null;
	friends?: Array<{ friend_uuid: string; friend_username: string; friend_skin_url: string | null; status: string }>;
	preferences?: Partial<Record<'default_expiry_ms' | 'notifications' | 'friend_requests', number>>;
}) {
	const storageValues = new Map<string, unknown>();
	let schemaInitialized = true;
	let schemaVersion = 6;
	const state = {
		profile: options.profile ?? null,
		friends: options.friends ? [...options.friends] : [],
		preferences: {
			default_expiry_ms: 86_400_000,
			notifications: 1,
			friend_requests: 1,
			...options.preferences,
		},
		sentSnaps: [] as Array<Record<string, unknown>>,
		receivedSnaps: [] as Array<Record<string, unknown>>,
		snapMetadata: [] as Array<Record<string, unknown>>,
		uploadAttempts: [] as Array<Record<string, unknown>>,
		abuseControls: {
			suspended_until: null as string | null,
			last_reason: '',
			updated_at: null as string | null,
		},
		storageValues,
		alarmAt: null as number | null,
	};

	const exec = vi.fn((query: string, ...params: unknown[]) => {
		if (query.includes('CREATE TABLE IF NOT EXISTS schema_version')) {
			return rowResult([]);
		}
		if (query.includes('SELECT version FROM schema_version LIMIT 1')) {
			return schemaVersion > 0 ? rowResult([{ version: schemaVersion }]) : rowResult([]);
		}
		if (query.includes('INSERT INTO schema_version (version) VALUES (?)')) {
			schemaVersion = Number(params[0]);
			schemaInitialized = true;
			return rowResult([]);
		}
		if (query.includes('UPDATE schema_version SET version = ?')) {
			schemaVersion = Number(params[0]);
			return rowResult([]);
		}
		if (
			query.includes('CREATE TABLE IF NOT EXISTS sessions')
			|| query.includes('CREATE TABLE IF NOT EXISTS profile')
			|| query.includes('CREATE TABLE IF NOT EXISTS friends')
			|| query.includes('CREATE TABLE IF NOT EXISTS received_snaps')
			|| query.includes('CREATE TABLE IF NOT EXISTS sent_snaps')
			|| query.includes('CREATE TABLE IF NOT EXISTS preferences')
			|| query.includes('CREATE TABLE IF NOT EXISTS snap_metadata')
			|| query.includes('CREATE TABLE IF NOT EXISTS upload_attempts')
			|| query.includes('CREATE TABLE IF NOT EXISTS abuse_controls')
			|| query.includes('CREATE INDEX IF NOT EXISTS')
			|| query.includes('DROP TABLE IF EXISTS upload_rate_limit_events')
			|| query.includes('DELETE FROM friends WHERE rowid NOT IN')
			|| query.includes('SET friend_uuid = replace(lower(friend_uuid), \'-\', \'\')')
			|| query.includes('UPDATE sent_snaps\n\t\tSET upload_id = snap_id')
			|| query.includes('UPDATE received_snaps\n\t\tSET upload_id = snap_id')
			|| query.includes('INSERT OR IGNORE INTO snap_metadata')
			|| query.startsWith('ALTER TABLE ')
		) {
			schemaInitialized = true;
			return rowResult([]);
		}
		if (query.includes('INSERT OR IGNORE INTO preferences (key, value) VALUES (?, ?)')) {
			state.preferences[String(params[0]) as keyof typeof state.preferences] = Number(params[1]);
			return rowResult([]);
		}
		if (query.includes('INSERT OR IGNORE INTO abuse_controls (id, suspended_until, last_reason) VALUES (1, NULL, \'\')')) {
			return rowResult([]);
		}
		if (!schemaInitialized) {
			throw new Error(`Schema missing for query: ${query}`);
		}
		if (query.includes('SELECT uuid, username, skin_url FROM profile LIMIT 1')) {
			return state.profile
				? rowResult([{ uuid: state.profile.uuid, username: state.profile.username, skin_url: state.profile.skin_url }])
				: rowResult([]);
		}
		if (query.includes('SELECT username, skin_url FROM profile LIMIT 1')) {
			return state.profile
				? rowResult([{ username: state.profile.username, skin_url: state.profile.skin_url }])
				: rowResult([]);
		}
		if (query.includes('SELECT skin_url FROM profile LIMIT 1')) {
			return state.profile
				? rowResult([{ skin_url: state.profile.skin_url }])
				: rowResult([]);
		}
		if (query.includes('INSERT INTO profile')) {
			state.profile = {
				uuid: String(params[0]),
				username: String(params[1]),
				skin_url: (params[2] as string | null) ?? null,
			};
			return rowResult([]);
		}
		if (query.includes('INSERT OR REPLACE INTO sessions (token, expires_at) VALUES (?, ?)')) {
			return rowResult([]);
		}
		if (query.includes('SELECT value FROM preferences WHERE key = ?')) {
			const key = String(params[0]) as keyof typeof state.preferences;
			const value = state.preferences[key];
			return rowResult(value === undefined ? [] : [{ value: String(value) }]);
		}
		if (query.includes('SELECT key, value FROM preferences')) {
			return rowResult(
				Object.entries(state.preferences).map(([key, value]) => ({
					key,
					value: String(value),
				}))
			);
		}
		if (query.includes("SELECT friend_username FROM friends WHERE friend_uuid = ? AND status = 'pending_incoming'")) {
			const uuid = String(params[0]);
			const rows = state.friends
				.filter((friend) => friend.friend_uuid === uuid && friend.status === 'pending_incoming')
				.map((friend) => ({ friend_username: friend.friend_username }));
			return rowResult(rows);
		}
		if (query.includes("SELECT 1 FROM friends WHERE friend_uuid = ? AND status = 'accepted'")) {
			const uuid = String(params[0]);
			const rows = state.friends
				.filter((friend) => friend.friend_uuid === uuid && friend.status === 'accepted')
				.map(() => ({ 1: 1 }));
			return rowResult(rows);
		}
		if (query.includes('SELECT status FROM friends WHERE friend_uuid = ?')) {
			const uuid = String(params[0]);
			const rows = state.friends.filter((friend) => friend.friend_uuid === uuid).map((friend) => ({ status: friend.status }));
			return rowResult(rows);
		}
		if (query.includes('SELECT 1 FROM friends WHERE friend_uuid = ?')) {
			const uuid = String(params[0]);
			const rows = state.friends.filter((friend) => friend.friend_uuid === uuid).map(() => ({ 1: 1 }));
			return rowResult(rows);
		}
		if (query.includes('SELECT friend_uuid, friend_username, friend_skin_url, status FROM friends')) {
			return rowResult(state.friends.map((friend) => ({ ...friend })));
		}
		if (query === 'SELECT friend_uuid FROM friends') {
			return rowResult(state.friends.map((friend) => ({ friend_uuid: friend.friend_uuid })));
		}
		if (query.includes('INSERT INTO friends')) {
			state.friends.push({
				friend_uuid: String(params[0]),
				friend_username: String(params[1]),
				friend_skin_url: (params[2] as string | null) ?? null,
				status: query.includes("pending_incoming") ? 'pending_incoming' : 'pending_outgoing',
			});
			return rowResult([]);
		}
		if (query.includes('UPDATE friends SET status = \'accepted\'')) {
			const uuid = String(params[params.length - 1]);
			for (const friend of state.friends) {
				if (friend.friend_uuid === uuid) {
					friend.status = 'accepted';
					if (params.length >= 3) {
						friend.friend_username = String(params[0]);
						friend.friend_skin_url = (params[1] as string | null) ?? null;
					}
				}
			}
			return rowResult([]);
		}
		if (query.includes('DELETE FROM friends WHERE friend_uuid = ?')) {
			const uuid = String(params[0]);
			state.friends = state.friends.filter((friend) => friend.friend_uuid !== uuid);
			return rowResult([]);
		}
		if (query.includes('INSERT INTO snap_metadata') || query.includes('INSERT OR REPLACE INTO snap_metadata')) {
			const uploadId = String(params[0]);
			const existing = state.snapMetadata.find((meta) => String(meta.upload_id) === uploadId);
			const next = {
				upload_id: params[0],
				from_uuid: params[1],
				media_type: params[2],
				content_type: params[3],
				media_size: params[4],
				media_width: params[5],
				media_height: params[6],
				caption_text: params[7],
				caption_offset_y: params[8],
				expiry_ms: params[9],
				sent_at: params[10],
				expires_at: params[11],
			};
			if (existing) {
				Object.assign(existing, next);
			} else {
				state.snapMetadata.push(next);
			}
			return rowResult([]);
		}
		if (
			query.includes('SELECT content_type, media_size, media_width, media_height, caption_text, caption_offset_y, expiry_ms, expires_at')
			&& query.includes('FROM snap_metadata')
		) {
			const uploadId = String(params[0]);
			const rows = state.snapMetadata
				.filter((meta) => meta.upload_id === uploadId)
				.map((meta) => ({
					content_type: meta.content_type,
					media_size: meta.media_size,
					media_width: (meta.media_width as number | undefined) ?? 0,
					media_height: (meta.media_height as number | undefined) ?? 0,
					caption_text: meta.caption_text,
					caption_offset_y: meta.caption_offset_y,
					expiry_ms: meta.expiry_ms,
					expires_at: meta.expires_at,
				}));
			return rowResult(rows);
		}
		if (query.includes('SELECT content_type, media_width, media_height, caption_text, caption_offset_y, expiry_ms') && query.includes('FROM snap_metadata')) {
			const uploadId = String(params[0]);
			const rows = state.snapMetadata
				.filter((meta) => meta.upload_id === uploadId)
				.map((meta) => ({
					content_type: meta.content_type,
					media_width: (meta.media_width as number | undefined) ?? 0,
					media_height: (meta.media_height as number | undefined) ?? 0,
					caption_text: meta.caption_text,
					caption_offset_y: meta.caption_offset_y,
					expiry_ms: meta.expiry_ms,
				}));
			return rowResult(rows);
		}
		if (query.includes('UPDATE snap_metadata') && query.includes('WHERE upload_id = ?')) {
			const uploadId = String(params[5]);
			for (const meta of state.snapMetadata) {
				if (String(meta.upload_id) === uploadId) {
					meta.media_type = params[0];
					meta.content_type = params[1];
					meta.media_size = params[2];
					meta.media_width = params[3];
					meta.media_height = params[4];
				}
			}
			return rowResult([]);
		}
		if (query.includes('SELECT') && query.includes('FROM upload_attempts')) {
			return rowResult(state.uploadAttempts.map((attempt) => ({ ...attempt })));
		}
		if (query.includes('INSERT OR REPLACE INTO upload_attempts')) {
			const uploadId = String(params[0]);
			const next = {
				upload_id: params[0],
				r2_key: params[1],
				hint_media_type: params[2],
				hint_content_type: params[3],
				reserved_size: params[4],
				actual_media_type: null,
				actual_content_type: null,
				actual_size: null,
				media_width: 0,
				media_height: 0,
				status: 'pending',
				last_error: '',
				created_at: params[5],
				updated_at: params[6],
				expires_at: params[7],
			};
			const existing = state.uploadAttempts.find((attempt) => String(attempt.upload_id) === uploadId);
			if (existing) {
				Object.assign(existing, next);
			} else {
				state.uploadAttempts.push(next);
			}
			return rowResult([]);
		}
		if (
			query.includes("UPDATE upload_attempts")
			&& query.includes("SET status = 'abandoned'")
			&& query.includes('WHERE upload_id = ?')
		) {
			const uploadId = String(params[2]);
			for (const attempt of state.uploadAttempts) {
				if (String(attempt.upload_id) === uploadId && String(attempt.status) === 'pending') {
					attempt.status = 'abandoned';
					attempt.updated_at = params[0];
					attempt.expires_at = params[1];
				}
			}
			return rowResult([]);
		}
		if (
			query.includes("UPDATE upload_attempts")
			&& query.includes("SET status = 'validated'")
			&& query.includes('WHERE upload_id = ?')
		) {
			const uploadId = String(params[7]);
			for (const attempt of state.uploadAttempts) {
				if (String(attempt.upload_id) === uploadId) {
					attempt.status = 'validated';
					attempt.actual_media_type = params[0];
					attempt.actual_content_type = params[1];
					attempt.actual_size = params[2];
					attempt.media_width = params[3];
					attempt.media_height = params[4];
					attempt.last_error = '';
					attempt.updated_at = params[5];
					attempt.expires_at = params[6];
				}
			}
			return rowResult([]);
		}
		if (
			query.includes("UPDATE upload_attempts")
			&& query.includes("SET status = 'invalid'")
			&& query.includes('WHERE upload_id = ?')
		) {
			const uploadId = String(params[4]);
			for (const attempt of state.uploadAttempts) {
				if (String(attempt.upload_id) === uploadId) {
					attempt.status = 'invalid';
					if (params[0] != null) {
						attempt.actual_size = params[0];
					}
					attempt.last_error = params[1];
					attempt.updated_at = params[2];
					attempt.expires_at = params[3];
				}
			}
			return rowResult([]);
		}
		if (query === 'DELETE FROM upload_attempts WHERE updated_at < ?') {
			const cutoff = String(params[0]);
			state.uploadAttempts = state.uploadAttempts.filter((attempt) => String(attempt.updated_at) >= cutoff);
			return rowResult([]);
		}
		if (query.includes('SELECT suspended_until FROM abuse_controls WHERE id = 1 LIMIT 1')) {
			return rowResult([{ suspended_until: state.abuseControls.suspended_until }]);
		}
		if (query.includes('UPDATE abuse_controls') && query.includes('WHERE id = 1')) {
			state.abuseControls.suspended_until = (params[0] as string | null) ?? null;
			state.abuseControls.last_reason = String(params[1] ?? '');
			state.abuseControls.updated_at = (params[2] as string | null) ?? null;
			return rowResult([]);
		}
		if (query.includes('INSERT INTO sent_snaps')) {
			state.sentSnaps.push({
				snap_id: params[0],
				upload_id: params[1],
				to_uuid: params[2],
				to_username: params[3],
				to_skin_url: (params[4] as string | null) ?? null,
				media_type: params[5],
				r2_key: params[6],
				content_type: params[7],
				media_size: params[8],
				sent_at: '2026-03-25T00:00:00.000Z',
				delivered_at: null,
				dropped_at: null,
				opened_at: null,
			});
			return rowResult([]);
		}
		if (query.includes('UPDATE sent_snaps') && query.includes('content_type = ?') && query.includes('WHERE upload_id = ?')) {
			const uploadId = String(params[3]);
			for (const snap of state.sentSnaps) {
				if (String(snap.upload_id) === uploadId) {
					snap.media_type = params[0];
					snap.content_type = params[1];
					snap.media_size = params[2];
				}
			}
			return rowResult([]);
		}
		if (query === 'SELECT upload_id, r2_key FROM sent_snaps') {
			return rowResult(state.sentSnaps.map((snap) => ({ upload_id: snap.upload_id, r2_key: snap.r2_key })));
		}
		if (query === 'SELECT to_uuid FROM sent_snaps') {
			return rowResult(state.sentSnaps.map((snap) => ({ to_uuid: snap.to_uuid })));
		}
		if (query === 'SELECT upload_id, r2_key FROM sent_snaps WHERE to_uuid = ?') {
			const toUuid = String(params[0]);
			return rowResult(
				state.sentSnaps
					.filter((snap) => snap.to_uuid === toUuid)
					.map((snap) => ({ upload_id: snap.upload_id, r2_key: snap.r2_key }))
			);
		}
		if (query.includes('SELECT *') && query.includes('FROM sent_snaps') && query.includes('upload_id = ?')) {
			const uploadId = String(params[0]);
			return rowResult(
				state.sentSnaps.filter(
					(snap) => snap.upload_id === uploadId && snap.delivered_at == null && snap.dropped_at == null
				)
			);
		}
		if (query.includes("UPDATE sent_snaps SET delivered_at = datetime('now') WHERE snap_id = ?")) {
			const snapId = String(params[0]);
			for (const snap of state.sentSnaps) {
				if (snap.snap_id === snapId) {
					snap.delivered_at = '2026-03-25T00:01:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (query.includes("UPDATE sent_snaps SET dropped_at = datetime('now') WHERE snap_id = ?")) {
			const snapId = String(params[0]);
			for (const snap of state.sentSnaps) {
				if (snap.snap_id === snapId) {
					snap.dropped_at = '2026-03-25T00:01:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (
			query.includes("UPDATE sent_snaps SET dropped_at = datetime('now') WHERE upload_id = ?")
			&& query.includes('opened_at IS NULL')
		) {
			const uploadId = String(params[0]);
			for (const snap of state.sentSnaps) {
				if (snap.upload_id === uploadId && snap.opened_at == null && snap.dropped_at == null) {
					snap.dropped_at = '2026-03-25T00:01:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (
			query.includes("UPDATE sent_snaps SET opened_at = datetime('now') WHERE upload_id = ? AND to_uuid = ?")
			&& query.includes('opened_at IS NULL')
		) {
			const uploadId = String(params[0]);
			const toUuid = String(params[1]);
			for (const snap of state.sentSnaps) {
				if (snap.upload_id === uploadId && snap.to_uuid === toUuid && snap.opened_at == null) {
					snap.opened_at = '2026-03-25T00:02:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (query.includes('DELETE FROM sent_snaps WHERE upload_id = ?')) {
			const uploadId = String(params[0]);
			state.sentSnaps = state.sentSnaps.filter((snap) => snap.upload_id !== uploadId);
			return rowResult([]);
		}
		if (query === 'DELETE FROM sent_snaps WHERE to_uuid = ?') {
			const toUuid = String(params[0]);
			state.sentSnaps = state.sentSnaps.filter((snap) => snap.to_uuid !== toUuid);
			return rowResult([]);
		}
		if (query.includes("DELETE FROM sessions WHERE expires_at < datetime('now')")) {
			return rowResult([]);
		}
		if (query.includes("SELECT snap_id FROM received_snaps WHERE viewed_at IS NOT NULL AND viewed_at < datetime('now', '-1 day')")) {
			return rowResult([]);
		}
		if (query.includes("DELETE FROM received_snaps WHERE viewed_at IS NOT NULL AND viewed_at < datetime('now', '-1 day')")) {
			return rowResult([]);
		}
		if (query.includes('SELECT r2_key FROM sent_snaps WHERE upload_id = ? LIMIT 1')) {
			const uploadId = String(params[0]);
			const row = state.sentSnaps.find((snap) => snap.upload_id === uploadId);
			return rowResult(row ? [{ r2_key: row.r2_key }] : []);
		}
		if (
			query.includes('AS delivered_count')
			&& query.includes('AS pending_count')
			&& query.includes('FROM sent_snaps')
			&& query.includes('WHERE upload_id = ?')
		) {
			const uploadId = String(params[0]);
			const summary = state.sentSnaps
				.filter((snap) => snap.upload_id === uploadId)
				.reduce(
					(acc, snap) => {
						if (snap.delivered_at != null) {
							acc.delivered_count += 1;
						}
						if (snap.delivered_at != null && snap.opened_at == null && snap.dropped_at == null) {
							acc.pending_count += 1;
						}
						return acc;
					},
					{ delivered_count: 0, pending_count: 0 }
				);
			return rowResult([summary]);
		}
		if (query.includes('SELECT 1 FROM sessions LIMIT 1')) {
			return rowResult([]);
		}
		if (query.includes('SELECT 1 FROM received_snaps LIMIT 1')) {
			return rowResult(state.receivedSnaps.length > 0 ? [{ 1: 1 }] : []);
		}
		if (query.includes('SELECT 1 FROM sent_snaps LIMIT 1')) {
			return rowResult(state.sentSnaps.length > 0 ? [{ 1: 1 }] : []);
		}
		if (query.includes('GROUP BY upload_id, r2_key')) {
			return rowResult([]);
		}
		if (query.includes('DELETE FROM snap_metadata WHERE upload_id = ?')) {
			const uploadId = String(params[0]);
			state.snapMetadata = state.snapMetadata.filter((meta) => meta.upload_id !== uploadId);
			return rowResult([]);
		}
		if (query.includes('INSERT INTO received_snaps')) {
			state.receivedSnaps.push({
				snap_id: params[0],
				upload_id: params[1],
				from_uuid: params[2],
				from_username: params[3],
				from_skin_url: (params[4] as string | null) ?? null,
				media_type: params[5],
				r2_key: params[6],
				sent_at: params[7],
				viewed_at: null,
			});
			return rowResult([]);
		}
		if (query === 'SELECT from_uuid FROM received_snaps') {
			return rowResult(state.receivedSnaps.map((snap) => ({ from_uuid: snap.from_uuid })));
		}
		if (query === 'SELECT upload_id FROM received_snaps WHERE from_uuid = ?') {
			const fromUuid = String(params[0]);
			return rowResult(
				state.receivedSnaps
					.filter((snap) => snap.from_uuid === fromUuid)
					.map((snap) => ({ upload_id: snap.upload_id }))
			);
		}
		if (query === 'DELETE FROM received_snaps WHERE from_uuid = ?') {
			const fromUuid = String(params[0]);
			state.receivedSnaps = state.receivedSnaps.filter((snap) => snap.from_uuid !== fromUuid);
			return rowResult([]);
		}
		if (query.includes("UPDATE received_snaps SET viewed_at = datetime('now') WHERE snap_id = ?")) {
			const snapId = String(params[0]);
			for (const snap of state.receivedSnaps) {
				if (snap.snap_id === snapId) {
					snap.viewed_at = '2026-03-25T00:03:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (
			query.includes('FROM received_snaps')
			&& query.includes('WHERE snap_id = ?')
			&& query.includes('viewed_at IS NULL')
			&& query.includes('from_uuid')
		) {
			const snapId = String(params[0]);
			const snap = state.receivedSnaps.find((s) => s.snap_id === snapId && s.viewed_at == null);
			if (!snap) {
				return rowResult([]);
			}
			const row: Record<string, unknown> = { from_uuid: snap.from_uuid };
			if (query.includes('upload_id')) {
				row.upload_id = snap.upload_id;
			}
			return rowResult([row]);
		}
		if (query === 'SELECT from_uuid, upload_id FROM received_snaps WHERE snap_id = ? AND viewed_at IS NULL') {
			const snapId = String(params[0]);
			const snap = state.receivedSnaps.find((s) => s.snap_id === snapId && s.viewed_at == null);
			if (!snap) {
				return rowResult([]);
			}
			return rowResult([{ from_uuid: snap.from_uuid, upload_id: snap.upload_id }]);
		}
		if (
			query.includes("UPDATE received_snaps SET viewed_at = datetime('now') WHERE upload_id = ?")
			&& query.includes('viewed_at IS NULL')
		) {
			const uploadId = String(params[0]);
			for (const snap of state.receivedSnaps) {
				if (snap.upload_id === uploadId && snap.viewed_at == null) {
					snap.viewed_at = '2026-03-25T00:03:00.000Z';
				}
			}
			return rowResult([]);
		}
		if (query.includes('FROM received_snaps rs') && query.includes('LEFT JOIN snap_metadata sm')) {
			if (query.includes('WHERE rs.snap_id = ?')) {
				const snapId = String(params[0]);
				const match = state.receivedSnaps.find((snap) => snap.snap_id === snapId && snap.viewed_at == null);
				if (!match) {
					return rowResult([]);
				}
				const meta = state.snapMetadata.find((entry) => entry.upload_id === match.upload_id);
				return rowResult([
					{
						snap_id: match.snap_id,
						upload_id: match.upload_id,
						from_uuid: match.from_uuid,
						from_username: match.from_username,
						from_skin_url: match.from_skin_url,
						r2_key: match.r2_key,
						media_type: match.media_type,
						sent_at: match.sent_at,
						content_type: meta?.content_type ?? '',
						media_width: (meta?.media_width as number | undefined) ?? 0,
						media_height: (meta?.media_height as number | undefined) ?? 0,
						caption_text: meta?.caption_text ?? '',
						caption_offset_y: meta?.caption_offset_y ?? 0,
						expiry_ms: meta?.expiry_ms ?? 86_400_000,
						expires_at: meta?.expires_at ?? null,
					},
				]);
			}
			const nowIso = String(params[0]);
			const rows = state.receivedSnaps
				.filter((snap) => {
					if (snap.viewed_at != null) {
						return false;
					}
					const meta = state.snapMetadata.find((entry) => entry.upload_id === snap.upload_id);
					if (!meta || meta.expires_at == null) {
						return true;
					}
					return String(meta.expires_at) > nowIso;
				})
				.sort((a, b) => {
					const uuidCompare = String(a.from_uuid).localeCompare(String(b.from_uuid));
					if (uuidCompare !== 0) return uuidCompare;
					const sentCompare = String(a.sent_at).localeCompare(String(b.sent_at));
					if (sentCompare !== 0) return sentCompare;
					return String(a.snap_id).localeCompare(String(b.snap_id));
				})
				.map((snap) => {
					const meta = state.snapMetadata.find((entry) => entry.upload_id === snap.upload_id);
					return {
						snap_id: snap.snap_id,
						upload_id: snap.upload_id,
						from_uuid: snap.from_uuid,
						from_username: snap.from_username,
						from_skin_url: snap.from_skin_url,
						media_type: snap.media_type,
						sent_at: snap.sent_at,
						content_type: meta?.content_type ?? '',
						media_width: (meta?.media_width as number | undefined) ?? 0,
						media_height: (meta?.media_height as number | undefined) ?? 0,
						caption_text: meta?.caption_text ?? '',
						caption_offset_y: meta?.caption_offset_y ?? 0,
						expiry_ms: meta?.expiry_ms ?? 86_400_000,
					};
				});
			return rowResult(rows);
		}
		if (query.includes('SELECT upload_id FROM snap_metadata WHERE expires_at <= ?')) {
			const nowIso = String(params[0]);
			const rows = state.snapMetadata
				.filter((meta) => typeof meta.expires_at === 'string' && String(meta.expires_at) <= nowIso)
				.map((meta) => ({ upload_id: meta.upload_id }));
			return rowResult(rows);
		}
		if (query === 'SELECT 1 FROM received_snaps WHERE upload_id = ? LIMIT 1') {
			const uploadId = String(params[0]);
			return rowResult(state.receivedSnaps.some((snap) => snap.upload_id === uploadId) ? [{ 1: 1 }] : []);
		}
		if (query === 'SELECT 1 FROM sent_snaps WHERE upload_id = ? LIMIT 1') {
			const uploadId = String(params[0]);
			return rowResult(state.sentSnaps.some((snap) => snap.upload_id === uploadId) ? [{ 1: 1 }] : []);
		}
		if (
			query.includes('SELECT to_uuid, to_username, to_skin_url, media_type, sent_at, opened_at, dropped_at')
			&& query.includes('FROM sent_snaps')
		) {
			return rowResult(
				[...state.sentSnaps]
					.sort((a, b) => String(b.sent_at).localeCompare(String(a.sent_at)))
					.map((snap) => ({ ...snap }))
			);
		}
		if (query.includes('SELECT from_uuid, from_username, from_skin_url, media_type, sent_at')) {
			return rowResult(
				[...state.receivedSnaps]
					.sort((a, b) => String(b.sent_at).localeCompare(String(a.sent_at)))
					.map((snap) => ({ ...snap }))
			);
		}
		throw new Error(`Unexpected SQL: ${query}`);
	});

	const dobj = Object.create(BlockChatUserDurableObject.prototype) as BlockChatUserDurableObject;
	dobj.sql = { exec } as unknown as SqlStorage;
	dobj.bindings = {
		USER_DO: {
			idFromName: vi.fn((name: string) => ({ name })),
			get: vi.fn(),
		},
		SNAPS_BUCKET: {} as R2Bucket,
	} as unknown as Env;
	Object.assign(dobj, { env: dobj.bindings });
	dobj.ctx = {
		id: { name: options.uuid },
		getWebSockets: () => [],
		storage: {
			getAlarm: vi.fn(async () => state.alarmAt),
			setAlarm: vi.fn(async (alarmAt: number) => {
				state.alarmAt = alarmAt;
			}),
			deleteAlarm: vi.fn(async () => {
				state.alarmAt = null;
			}),
			get: vi.fn(async (key: string) => storageValues.get(key)),
			put: vi.fn(async (key: string, value: unknown) => {
				storageValues.set(key, value);
			}),
			delete: vi.fn(async (key: string) => {
				storageValues.delete(key);
			}),
				deleteAll: vi.fn(async () => {
					storageValues.clear();
					state.alarmAt = null;
					state.profile = null;
					state.friends = [];
					state.sentSnaps = [];
					state.receivedSnaps = [];
					state.snapMetadata = [];
					state.uploadAttempts = [];
					state.abuseControls = {
						suspended_until: null,
						last_reason: '',
						updated_at: null,
					};
					state.preferences = {
						default_expiry_ms: 86_400_000,
						notifications: 1,
						friend_requests: 1,
					};
					schemaInitialized = false;
					schemaVersion = 0;
				}),
			},
		} as unknown as DurableObjectState;

	// Initialize private in-memory rate-limit arrays (normally set by the constructor).
	Object.assign(dobj, { uploadTimestamps: [], recentUserSearches: [] });

	return { dobj, state, exec };
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe('BlockChat worker', () => {
	it('serves the BlockChat homepage at / (unit style)', async () => {
		const request = new IncomingRequest('http://example.com');
		const ctx = createExecutionContext();
		const response = await worker.fetch(request, env, ctx);
		await waitOnExecutionContext(ctx);

		expect(response.headers.get('content-type')).toBe('text/html;charset=UTF-8');

		const html = await response.text();
		expect(html).toContain('<h1>BlockChat</h1>');
		expect(html).toContain(
			'A social mod for Minecraft where you can instantly send screenshots and videos to friends.'
		);
		expect(html).toContain('href="https://youtube.com/@DesertReet"');
		expect(html).toContain('>Desert Reet</a>');
		expect(html).toContain('href="/privacy-policy"');
		expect(html).toContain('href="/terms-of-service"');
	});

	it('serves the BlockChat homepage at / (integration style)', async () => {
		const response = await SELF.fetch('https://example.com');
		const html = await response.text();

		expect(response.headers.get('content-type')).toBe('text/html;charset=UTF-8');
		expect(html).toContain('<title>BlockChat</title>');
		expect(html).toContain('Created by');
		expect(html).toContain('https://youtube.com/@DesertReet');
	});

	it('serves the privacy policy at /privacy-policy', async () => {
		const response = await SELF.fetch('https://example.com/privacy-policy');
		const html = await response.text();

		expect(response.headers.get('content-type')).toBe('text/html;charset=UTF-8');
		expect(html).toContain('<title>Privacy Policy — BlockChat</title>');
		expect(html).toContain('TL;DR');
		expect(html).toContain('train machine learning');
		expect(html).toContain('href="/terms-of-service"');
		expect(html).toContain('href="/license"');
	});

	it('serves the license at /license', async () => {
		const response = await SELF.fetch('https://example.com/license');
		const html = await response.text();

		expect(response.headers.get('content-type')).toBe('text/html;charset=UTF-8');
		expect(html).toContain('<title>MIT License — BlockChat</title>');
		expect(html).toContain('<h1>MIT License</h1>');
		expect(html).toContain('Permission is hereby granted, free of charge');
	});

	it('serves the terms of service at /terms-of-service', async () => {
		const response = await SELF.fetch('https://example.com/terms-of-service');
		const html = await response.text();

		expect(response.headers.get('content-type')).toBe('text/html;charset=UTF-8');
		expect(html).toContain('<title>Terms of Service — BlockChat</title>');
		expect(html).toContain('TL;DR');
		expect(html).toContain('rate limits');
		expect(html).toContain('href="/privacy-policy"');
		expect(html).toContain('href="/license"');
	});

	it('returns 404 for non-root paths', async () => {
		const response = await SELF.fetch('https://example.com/nope');

		expect(response.status).toBe(404);
		expect(await response.text()).toBe('Not Found');
	});

	it('returns 405 for non-GET/HEAD on homepage', async () => {
		const response = await SELF.fetch('https://example.com/', { method: 'POST' });

		expect(response.status).toBe(405);
		expect(response.headers.get('allow')).toBe('GET, HEAD');
	});

	it('returns 405 for GET on /api/auth/device-code', async () => {
		const response = await SELF.fetch('https://example.com/api/auth/device-code');

		expect(response.status).toBe(405);
		expect(response.headers.get('allow')).toBe('POST');
	});

	it('returns 405 for GET on /api/auth/verify', async () => {
		const response = await SELF.fetch('https://example.com/api/auth/verify');

		expect(response.status).toBe(405);
		expect(response.headers.get('allow')).toBe('POST');
	});

	it('returns 400 for /api/auth/verify with missing device_code', async () => {
		const request = new IncomingRequest('https://example.com/api/auth/verify', {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({}),
		});
		const ctx = createExecutionContext();
		const fakeEnv = { ...env, TEST_MODE: '0' } as Env;
		const response = await worker.fetch(request, fakeEnv, ctx);
		await waitOnExecutionContext(ctx);

		expect(response.status).toBe(400);
		const body = await response.json();
		expect(body.error).toBe('missing_device_code');
	});

	it('returns 400 for /api/auth/verify with invalid JSON', async () => {
		const response = await SELF.fetch('https://example.com/api/auth/verify', {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: 'not json',
		});

		expect(response.status).toBe(400);
		const body = await response.json();
		expect(body.error).toBe('invalid_json');
	});

	it('signs upload URLs with content headers so media constraints are enforced by R2', async () => {
		const fakeEnv = {
			R2_ACCESS_KEY_ID: 'AKIAEXAMPLE',
			R2_SECRET_ACCESS_KEY: 'secret',
			R2_ACCOUNT_ID: 'acct',
			R2_BUCKET_NAME: 'bucket',
		} as Env;

		const client = presign.createR2Client(fakeEnv);
		const signedUrl = await presign.generateUploadUrl(client, fakeEnv, 'snaps/test/snap-id', 'image/png', 123);
		const url = new URL(signedUrl);

		expect(url.searchParams.get('X-Amz-SignedHeaders')).toBe('content-length;content-type;host');
		expect(url.searchParams.get('x-amz-content-type')).toBeNull();
		expect(url.searchParams.get('x-amz-content-length')).toBeNull();
	});

	it('fetches a Mojang public profile and extracts the skin URL', async () => {
		const fetchMock = vi
			.spyOn(globalThis, 'fetch')
			.mockResolvedValueOnce(
				new Response(JSON.stringify({ id: 'abc123', name: 'DesertReet' }), { status: 200 })
			)
			.mockResolvedValueOnce(
				new Response(
					JSON.stringify({
						properties: [
							{
								name: 'textures',
								value: btoa(JSON.stringify({ textures: { SKIN: { url: 'https://textures.minecraft.net/skin' } } })),
							},
						],
					}),
					{ status: 200 }
				)
			);

		const profile = await fetchMojangPublicProfile('desertreet');

		expect(fetchMock).toHaveBeenCalledTimes(2);
		expect(profile).toEqual({
			uuid: 'abc123',
			username: 'DesertReet',
			skinUrl: 'https://textures.minecraft.net/skin',
		});
	});

	it('fetches a Mojang public profile by UUID and extracts the skin URL', async () => {
		const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
			new Response(
				JSON.stringify({
					id: RECIPIENT_UUID,
					name: BOOTSTRAPPED_USERNAME,
					properties: [
						{
							name: 'textures',
							value: btoa(JSON.stringify({ textures: { SKIN: { url: BOOTSTRAPPED_SKIN } } })),
						},
					],
				}),
				{ status: 200 }
			)
		);

		const profile = await fetchMojangPublicProfileByUuid(RECIPIENT_UUID);

		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(profile).toEqual({
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
			skinUrl: BOOTSTRAPPED_SKIN,
		});
	});

	it('returns a websocket search result for a found username', async () => {
		vi.spyOn(globalThis, 'fetch')
			.mockResolvedValueOnce(new Response(JSON.stringify({ id: 'abc123', name: 'DesertReet' }), { status: 200 }))
			.mockResolvedValueOnce(
				new Response(
					JSON.stringify({
						properties: [
							{
								name: 'textures',
								value: btoa(JSON.stringify({ textures: { SKIN: { url: 'https://textures.minecraft.net/skin' } } })),
							},
						],
					}),
					{ status: 200 }
				)
			);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const dobj = {
			consumeUserSearchSlot: () => true,
		} as BlockChatUserDurableObject;

		await handleClientMessage(dobj, ws, { type: 'search_user', username: 'desertreet' });

		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'user_search_result',
			query: 'desertreet',
			user: {
				uuid: 'abc123',
				username: 'DesertReet',
				skin_url: 'https://textures.minecraft.net/skin',
			},
		});
	});

	it('returns a websocket search result with null user when the username does not exist', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(null, { status: 404 }));

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const dobj = {
			consumeUserSearchSlot: () => true,
		} as BlockChatUserDurableObject;

		await handleClientMessage(dobj, ws, { type: 'search_user', username: 'missing-user' });

		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'user_search_result',
			query: 'missing-user',
			user: null,
		});
	});

	it('rate limits websocket username search in the DO', async () => {
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const dobj = {
			consumeUserSearchSlot: () => false,
		} as BlockChatUserDurableObject;

		await handleClientMessage(dobj, ws, { type: 'search_user', username: 'desertreet' });

		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'search_rate_limited',
			message: 'Search limit exceeded. Please try again later.',
		});
	});

	it('returns all websocket preferences from the DO snapshot', async () => {
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
			const dobj = {
				getPreferencesSnapshot: () => ({
					default_expiry_ms: 86_400_000,
					notifications: 1,
					friend_requests: 0,
				}),
		} as BlockChatUserDurableObject;

		await handleClientMessage(dobj, ws, { type: 'get_preferences' });

		expect(sent).toHaveLength(1);
			expect(JSON.parse(sent[0])).toEqual({
				type: 'preferences',
				preferences: {
					default_expiry_ms: 86_400_000,
					notifications: 1,
					friend_requests: 0,
				},
			});
	});

	it('stores notifications preference updates via the generic preference flow', async () => {
		const exec = vi.fn();
		const dobj = {
			sql: { exec },
		} as unknown as BlockChatUserDurableObject;
		const ws = { send: vi.fn() } as unknown as WebSocket;

		await handleClientMessage(dobj, ws, {
			type: 'set_preference',
			key: 'notifications',
			value: 0,
		});

		expect(exec).toHaveBeenCalledWith(
			expect.stringContaining('INSERT INTO preferences'),
			'notifications',
			'0'
		);
	});

	it('bootstraps a blank recipient DO for add_friend and emits a pending-request success signal', async () => {
		const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
			new Response(
				JSON.stringify({
					id: RECIPIENT_UUID,
					name: BOOTSTRAPPED_USERNAME,
					properties: [
						{
							name: 'textures',
							value: btoa(JSON.stringify({ textures: { SKIN: { url: BOOTSTRAPPED_SKIN } } })),
						},
					],
				}),
				{ status: 200 }
			)
		);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		await handleClientMessage(sender.dobj, ws, { type: 'add_friend', uuid: RECIPIENT_UUID });

		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(recipient.state.profile).toEqual({
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
			skin_url: BOOTSTRAPPED_SKIN,
		});
		expect(recipient.state.friends).toEqual([
			{
				friend_uuid: SENDER_UUID,
				friend_username: 'SenderPlayer',
				friend_skin_url: null,
				status: 'pending_incoming',
			},
		]);
		expect(sender.state.friends).toEqual([
			{
				friend_uuid: RECIPIENT_UUID,
				friend_username: BOOTSTRAPPED_USERNAME,
				friend_skin_url: BOOTSTRAPPED_SKIN,
				status: 'pending_outgoing',
			},
		]);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'friend_request_sent',
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
		});
	});

	it('returns pending_outgoing in the friend list after add_friend succeeds', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
			new Response(
				JSON.stringify({
					id: RECIPIENT_UUID,
					name: BOOTSTRAPPED_USERNAME,
					properties: [
						{
							name: 'textures',
							value: btoa(JSON.stringify({ textures: { SKIN: { url: BOOTSTRAPPED_SKIN } } })),
						},
					],
				}),
				{ status: 200 }
			)
		);

		const recipient = createStatefulUserDo({ uuid: RECIPIENT_UUID });
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		await handleClientMessage(sender.dobj, ws, { type: 'add_friend', uuid: RECIPIENT_UUID });
		sent.length = 0;

		await handleClientMessage(sender.dobj, ws, { type: 'get_friends' });

		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'friend_list',
			friends: [
				{
					uuid: RECIPIENT_UUID,
					username: BOOTSTRAPPED_USERNAME,
					skin_url: BOOTSTRAPPED_SKIN,
					status: 'pending_outgoing',
				},
			],
		});
	});

	it('still emits friend_added when accept_friend completes', async () => {
		const recipientSent: string[] = [];
		const senderSent: string[] = [];
		const recipientWs = { send: (message: string) => recipientSent.push(message) } as unknown as WebSocket;
		const senderWs = { send: (message: string) => senderSent.push(message) } as unknown as WebSocket;
		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: {
				uuid: RECIPIENT_UUID,
				username: BOOTSTRAPPED_USERNAME,
				skin_url: BOOTSTRAPPED_SKIN,
			},
			friends: [
				{
					friend_uuid: SENDER_UUID,
					friend_username: 'SenderPlayer',
					friend_skin_url: null,
					status: 'pending_incoming',
				},
			],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
			friends: [
				{
					friend_uuid: RECIPIENT_UUID,
					friend_username: BOOTSTRAPPED_USERNAME,
					friend_skin_url: BOOTSTRAPPED_SKIN,
					status: 'pending_outgoing',
				},
			],
		});
		recipient.dobj.bindings.USER_DO.get = vi.fn(() => sender.dobj);
		sender.dobj.ctx = {
			...sender.dobj.ctx,
			getWebSockets: () => [senderWs],
		} as DurableObjectState;

		await handleClientMessage(recipient.dobj, recipientWs, { type: 'accept_friend', uuid: SENDER_UUID });

		expect(recipient.state.friends).toEqual([
			{
				friend_uuid: SENDER_UUID,
				friend_username: 'SenderPlayer',
				friend_skin_url: null,
				status: 'accepted',
			},
		]);
		expect(sender.state.friends).toEqual([
			{
				friend_uuid: RECIPIENT_UUID,
				friend_username: BOOTSTRAPPED_USERNAME,
				friend_skin_url: BOOTSTRAPPED_SKIN,
				status: 'accepted',
			},
		]);
		expect(recipientSent).toHaveLength(1);
		expect(JSON.parse(recipientSent[0])).toEqual({
			type: 'friend_added',
			uuid: SENDER_UUID,
			username: 'SenderPlayer',
		});
		expect(senderSent).toHaveLength(1);
		expect(JSON.parse(senderSent[0])).toEqual({
			type: 'friend_added',
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
		});
	});

	it('treats malformed recipient UUID bootstrap failures as user_not_found', async () => {
		const fetchMock = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('unexpected fetch'));

		const recipient = createStatefulUserDo({ uuid: 'not-a-valid-uuid' });
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		await handleClientMessage(sender.dobj, ws, { type: 'add_friend', uuid: 'not-a-valid-uuid' });

		expect(fetchMock).not.toHaveBeenCalled();
		expect(recipient.state.profile).toBeNull();
		expect(sender.state.friends).toEqual([]);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'user_not_found',
			message: 'User not found',
		});
	});

	it('returns an accepted friend as a snap target and reuses it for send_snap', async () => {
		const uploadMock = vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: BOOTSTRAPPED_USERNAME, skin_url: BOOTSTRAPPED_SKIN },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', null)],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
			friends: [acceptedFriend(RECIPIENT_UUID, BOOTSTRAPPED_USERNAME, BOOTSTRAPPED_SKIN)],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		const target = await recipient.dobj.getSendSnapTarget(SENDER_UUID);

		expect(target).toEqual({
			status: 'ready',
			username: BOOTSTRAPPED_USERNAME,
			skin_url: BOOTSTRAPPED_SKIN,
		});

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(uploadMock).toHaveBeenCalledTimes(1);
		expect(sender.state.sentSnaps).toEqual([
			{
				snap_id: expect.any(String),
				upload_id: expect.any(String),
				to_uuid: RECIPIENT_UUID,
				to_username: BOOTSTRAPPED_USERNAME,
				to_skin_url: BOOTSTRAPPED_SKIN,
				media_type: 'image',
				r2_key: expect.stringMatching(/^snaps\/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\//),
				content_type: 'image/png',
				media_size: 123,
				sent_at: '2026-03-25T00:00:00.000Z',
				delivered_at: null,
				dropped_at: null,
				opened_at: null,
			},
		]);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'snap_upload_url',
			snap_id: expect.any(String),
			upload_url: 'https://upload.example/snap',
			expires_in: expect.any(Number),
		});
	});

	it('rejects zero-byte send_snap requests before presigning', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: BOOTSTRAPPED_USERNAME, skin_url: BOOTSTRAPPED_SKIN },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', null)],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
			friends: [acceptedFriend(RECIPIENT_UUID, BOOTSTRAPPED_USERNAME, BOOTSTRAPPED_SKIN)],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'image',
			media_size: 0,
			content_type: 'image/png',
		});

		expect(sender.state.sentSnaps).toHaveLength(0);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'invalid_media_size',
			message: 'media_size must be a positive integer greater than zero',
		});
	});

	it('rejects send_snap when media_type does not match content_type', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: BOOTSTRAPPED_USERNAME, skin_url: BOOTSTRAPPED_SKIN },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', null)],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
			friends: [acceptedFriend(RECIPIENT_UUID, BOOTSTRAPPED_USERNAME, BOOTSTRAPPED_SKIN)],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'video',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(sender.state.sentSnaps).toHaveLength(0);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'invalid_media_type',
			message: 'media_type must match the provided content_type',
		});
	});

	it('stores one sender row per recipient and reuses one upload for multi-recipient send_snap', async () => {
		const uploadMock = vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipientOne = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'FirstTarget', skin_url: 'https://textures.minecraft.net/skin/one' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		const recipientTwo = createStatefulUserDo({
			uuid: RECIPIENT_TWO_UUID,
			profile: { uuid: RECIPIENT_TWO_UUID, username: 'SecondTarget', skin_url: 'https://textures.minecraft.net/skin/two' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [
				acceptedFriend(RECIPIENT_UUID, 'FirstTarget', 'https://textures.minecraft.net/skin/one'),
				acceptedFriend(RECIPIENT_TWO_UUID, 'SecondTarget', 'https://textures.minecraft.net/skin/two'),
			],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn((id: { name: string }) =>
			id.name === RECIPIENT_UUID ? recipientOne.dobj : recipientTwo.dobj
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;

		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID, RECIPIENT_TWO_UUID],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(uploadMock).toHaveBeenCalledTimes(1);
		expect(sender.state.sentSnaps).toHaveLength(2);
		expect(sender.state.sentSnaps[0].upload_id).toBe(sender.state.sentSnaps[1].upload_id);
		expect(sender.state.sentSnaps[0].r2_key).toBe(sender.state.sentSnaps[1].r2_key);
		expect(sender.state.sentSnaps.map((snap) => snap.to_uuid)).toEqual([RECIPIENT_UUID, RECIPIENT_TWO_UUID]);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'snap_upload_url',
			snap_id: sender.state.sentSnaps[0].upload_id,
			upload_url: 'https://upload.example/snap',
			expires_in: expect.any(Number),
		});
	});

	it('rejects send_snap when recipients include the sender UUID', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const user = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SoloPlayer', skin_url: null },
		});
		user.dobj.bindings.USER_DO.get = vi.fn(() => user.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;

		await handleClientMessage(user.dobj, ws, {
			type: 'send_snap',
			to: [SENDER_UUID],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(user.state.sentSnaps).toHaveLength(0);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'cannot_send_to_self',
			message: 'You cannot send snaps to yourself',
		});
	});

	it('receiveSnap rejects when the sender is the same user as this DO', async () => {
		const user = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SoloPlayer', skin_url: null },
		});

		const result = await user.dobj.receiveSnap({
			snap_id: 'snap-self',
			upload_id: 'upload-self',
			from_uuid: SENDER_UUID,
			from_username: 'SoloPlayer',
			from_skin_url: null,
			media_type: 'image',
			r2_key: 'snaps/key',
			content_type: 'image/png',
			media_size: 1,
			caption_text: '',
			caption_offset_y: 0,
			expiry_ms: 86_400_000,
			expires_at: '2026-03-26T00:00:00.000Z',
			sent_at: '2026-03-25T00:00:00.000Z',
		});

		expect(result).toBe('rejected');
		expect(user.state.receivedSnaps).toHaveLength(0);
	});

	it('includes media dimensions in the snap_received notification and stored metadata', async () => {
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const user = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		user.dobj.ctx = {
			...user.dobj.ctx,
			getWebSockets: () => [ws],
		} as DurableObjectState;

		const result = await user.dobj.receiveSnap({
			snap_id: 'snap-dims',
			upload_id: 'upload-dims',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'video',
			r2_key: 'snaps/dims',
			content_type: 'video/mp4',
			media_size: 1234,
			media_width: 1920,
			media_height: 1080,
			caption_text: 'dimension caption',
			caption_offset_y: 0.15,
			expiry_ms: 3_600_000,
			expires_at: '2099-01-01T00:00:00.000Z',
			sent_at: '2026-03-25T00:00:00.000Z',
		});

		expect(result).toBe('delivered');
		expect(user.state.snapMetadata).toHaveLength(1);
		expect(user.state.snapMetadata[0]).toEqual({
			upload_id: 'upload-dims',
			from_uuid: SENDER_UUID,
			media_type: 'video',
			content_type: 'video/mp4',
			media_size: 1234,
			media_width: 1920,
			media_height: 1080,
			caption_text: 'dimension caption',
			caption_offset_y: 0.15,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:00:00.000Z',
			expires_at: '2099-01-01T00:00:00.000Z',
		});
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'snap_received',
			snap_id: 'snap-dims',
			upload_id: 'upload-dims',
			from: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'video',
			content_type: 'video/mp4',
			media_width: 1920,
			media_height: 1080,
			caption_text: 'dimension caption',
			caption_offset_y: 0.15,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:00:00.000Z',
		});
	});

	it('rejects snap sends when any recipient is not an accepted friend', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipientEveryone = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'EveryoneTarget', skin_url: 'https://textures.minecraft.net/skin/one' },
		});
		const recipientFriendsOnly = createStatefulUserDo({
			uuid: RECIPIENT_TWO_UUID,
			profile: { uuid: RECIPIENT_TWO_UUID, username: 'FriendsOnly', skin_url: 'https://textures.minecraft.net/skin/two' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [acceptedFriend(RECIPIENT_TWO_UUID, 'FriendsOnly', 'https://textures.minecraft.net/skin/two')],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn((id: { name: string }) =>
			id.name === RECIPIENT_UUID ? recipientEveryone.dobj : recipientFriendsOnly.dobj
		);
		sender.dobj.bindings.SNAPS_BUCKET = {
			head: vi.fn(async () => ({ size: 123, httpMetadata: { contentType: 'image/png' } })),
		} as unknown as R2Bucket;

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;

		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID, RECIPIENT_TWO_UUID],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(sender.state.sentSnaps).toHaveLength(0);
		expect(recipientEveryone.state.receivedSnaps).toHaveLength(0);
		expect(recipientFriendsOnly.state.receivedSnaps).toHaveLength(0);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'not_friends',
			message: 'You can only send snaps to accepted friends',
		});
	});

	it('stores shared snap metadata from send_snap payload fields', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'TargetPlayer', skin_url: 'https://textures.minecraft.net/skin/target' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [acceptedFriend(RECIPIENT_UUID, 'TargetPlayer', 'https://textures.minecraft.net/skin/target')],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'video',
			media_size: 456,
			content_type: 'video/mp4',
			media_width: 1920,
			media_height: 1080,
			caption_text: 'This is a caption',
			caption_offset_y: 0.35,
			expiry_ms: 3_600_000,
		});

		expect(sender.state.snapMetadata).toHaveLength(1);
		expect(sender.state.snapMetadata[0]).toEqual({
			upload_id: expect.any(String),
			from_uuid: SENDER_UUID,
			media_type: 'video',
			content_type: 'video/mp4',
			media_size: 456,
			media_width: 1920,
			media_height: 1080,
			caption_text: 'This is a caption',
			caption_offset_y: 0.35,
			expiry_ms: 86_400_000,
			sent_at: expect.any(String),
			expires_at: expect.any(String),
		});
		expect(JSON.parse(sent[0])).toEqual({
			type: 'snap_upload_url',
			snap_id: expect.any(String),
			upload_url: 'https://upload.example/snap',
			expires_in: expect.any(Number),
		});
	});

	it('replaces hinted media metadata with server-detected values on snap_uploaded', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const jpegBytes = makeMinimalJpeg(3, 2);
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [acceptedFriend(RECIPIENT_UUID, 'TargetPlayer', 'https://textures.minecraft.net/skin/target')],
		});
		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'TargetPlayer', skin_url: 'https://textures.minecraft.net/skin/target' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn((id: { name: string }) =>
			id.name === RECIPIENT_UUID ? recipient.dobj : sender.dobj
		);

		const r2 = createMockR2Bucket(jpegBytes, 'image/png');
		sender.dobj.bindings.SNAPS_BUCKET = r2.bucket;

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'image',
			media_size: jpegBytes.byteLength,
			content_type: 'image/png',
			media_width: 999,
			media_height: 777,
			caption_text: 'hint caption',
		});

		const uploadId = String(sender.state.sentSnaps[0].upload_id);
		await handleClientMessage(sender.dobj, ws, { type: 'snap_uploaded', snap_id: uploadId });

		expect(sender.state.snapMetadata[0]).toEqual(
			expect.objectContaining({
				upload_id: uploadId,
				content_type: 'image/jpeg',
				media_type: 'image',
				media_size: jpegBytes.byteLength,
				media_width: 3,
				media_height: 2,
			})
		);
		expect(sender.state.sentSnaps[0]).toEqual(
			expect.objectContaining({
				upload_id: uploadId,
				content_type: 'image/jpeg',
				media_type: 'image',
				media_size: jpegBytes.byteLength,
				delivered_at: '2026-03-25T00:01:00.000Z',
			})
		);
		expect(recipient.state.snapMetadata[0]).toEqual(
			expect.objectContaining({
				upload_id: uploadId,
				content_type: 'image/jpeg',
				media_type: 'image',
				media_size: jpegBytes.byteLength,
				media_width: 3,
				media_height: 2,
			})
		);
		expect(r2.getStoredContentType()).toBe('image/jpeg');
		expect(r2.putMock).toHaveBeenCalledTimes(1);
		expect(JSON.parse(sent[sent.length - 1])).toEqual({
			type: 'snap_delivered',
			snap_id: uploadId,
		});
	});

	it('blocks new uploads when durable invalid-upload strikes trigger a suspension', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: BOOTSTRAPPED_USERNAME, skin_url: BOOTSTRAPPED_SKIN },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', null)],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
			friends: [acceptedFriend(RECIPIENT_UUID, BOOTSTRAPPED_USERNAME, BOOTSTRAPPED_SKIN)],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);
		sender.state.uploadAttempts.push(
			...Array.from({ length: 5 }, (_, index) => ({
				upload_id: `invalid-${index}`,
				r2_key: `snaps/${SENDER_UUID}/invalid-${index}`,
				hint_media_type: 'image',
				hint_content_type: 'image/png',
				reserved_size: 128,
				actual_media_type: null,
				actual_content_type: null,
				actual_size: null,
				media_width: 0,
				media_height: 0,
				status: 'invalid',
				last_error: 'bad upload',
				created_at: '2099-01-01T00:00:00.000Z',
				updated_at: '2099-01-01T00:00:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			}))
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'upload_suspended',
			message: expect.stringContaining('Uploads are temporarily suspended until'),
		});
		expect(sender.state.uploadAttempts).toHaveLength(5);
		expect(sender.state.abuseControls.suspended_until).toEqual(expect.any(String));
	});

	it('defaults invalid send expiry to one day instead of rejecting', async () => {
		vi.spyOn(presign, 'generateUploadUrl').mockResolvedValue('https://upload.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'TargetPlayer', skin_url: 'https://textures.minecraft.net/skin/target' },
			friends: [acceptedFriend(SENDER_UUID, 'SenderPlayer', 'https://textures.minecraft.net/skin/sender')],
		});
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [acceptedFriend(RECIPIENT_UUID, 'TargetPlayer', 'https://textures.minecraft.net/skin/target')],
		});
		sender.dobj.bindings.USER_DO.get = vi.fn(() => recipient.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [RECIPIENT_UUID],
			media_type: 'image',
			media_size: 456,
			content_type: 'image/png',
			expiry_ms: -1,
		} as never);

		expect(sender.state.snapMetadata).toHaveLength(1);
		expect(sender.state.snapMetadata[0].expiry_ms).toBe(86_400_000);
		expect(JSON.parse(sent[0])).toEqual(
			expect.objectContaining({
				type: 'snap_upload_url',
				snap_id: expect.any(String),
				expires_in: expect.any(Number),
			})
		);
	});

	it('returns snap metadata from view_snap and signs download URL with snap expiry TTL', async () => {
		const downloadMock = vi.spyOn(presign, 'generateDownloadUrl').mockResolvedValue('https://download.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);

		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: 'https://textures.minecraft.net/skin/recipient' },
		});
		dobj.state.receivedSnaps.push({
			snap_id: 'snap-view-1',
			upload_id: 'upload-view-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'video',
			r2_key: 'snaps/a/upload-view-1',
			sent_at: '2026-03-25T00:10:00.000Z',
			viewed_at: null,
		});
		dobj.state.snapMetadata.push({
			upload_id: 'upload-view-1',
			from_uuid: SENDER_UUID,
			media_type: 'video',
			content_type: 'video/mp4',
			media_size: 999,
			caption_text: 'view caption',
			caption_offset_y: 0.2,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:10:00.000Z',
			expires_at: '2099-01-01T00:00:00.000Z',
		});

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'view_snap', snap_id: 'snap-view-1' });

		expect(downloadMock).toHaveBeenCalledWith(expect.anything(), expect.anything(), 'snaps/a/upload-view-1', 3600);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'snap_download_url',
			snap_id: 'snap-view-1',
			upload_id: 'upload-view-1',
			download_url: 'https://download.example/snap',
			expires_in: 3600,
			media_type: 'video',
			content_type: 'video/mp4',
			media_width: 0,
			media_height: 0,
			caption_text: 'view caption',
			caption_offset_y: 0.2,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:10:00.000Z',
		});
	});

	it('deletes a shared snap immediately after the last delivered recipient views it', async () => {
		const downloadMock = vi.spyOn(presign, 'generateDownloadUrl').mockResolvedValue('https://download.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);
		const deleteMock = vi.fn(async () => undefined);

		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [
				acceptedFriend(RECIPIENT_UUID, 'FirstTarget', 'https://textures.minecraft.net/skin/one'),
				acceptedFriend(RECIPIENT_TWO_UUID, 'SecondTarget', 'https://textures.minecraft.net/skin/two'),
			],
		});
		sender.state.sentSnaps.push(
			{
				snap_id: 'snap-view-one',
				upload_id: 'upload-shared-1',
				to_uuid: RECIPIENT_UUID,
				to_username: 'RecipientPlayerOne',
				to_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/a/upload-shared-1',
				content_type: 'image/png',
				media_size: 1,
				sent_at: '2026-03-25T00:00:00.000Z',
				delivered_at: '2026-03-25T00:01:00.000Z',
				dropped_at: null,
				opened_at: null,
			},
			{
				snap_id: 'snap-view-two',
				upload_id: 'upload-shared-1',
				to_uuid: RECIPIENT_TWO_UUID,
				to_username: 'RecipientPlayerTwo',
				to_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/a/upload-shared-1',
				content_type: 'image/png',
				media_size: 1,
				sent_at: '2026-03-25T00:00:00.000Z',
				delivered_at: '2026-03-25T00:02:00.000Z',
				dropped_at: null,
				opened_at: null,
			}
		);
		sender.dobj.bindings.SNAPS_BUCKET = {
			delete: deleteMock,
		} as unknown as R2Bucket;

		const recipientOne = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientOne', skin_url: null },
		});
		recipientOne.state.receivedSnaps.push({
			snap_id: 'snap-view-one',
			upload_id: 'upload-shared-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'image',
			r2_key: 'snaps/a/upload-shared-1',
			sent_at: '2026-03-25T00:00:00.000Z',
			viewed_at: null,
		});
		recipientOne.state.snapMetadata.push({
			upload_id: 'upload-shared-1',
			from_uuid: SENDER_UUID,
			media_type: 'image',
			content_type: 'image/png',
			media_size: 1,
			caption_text: 'shared caption',
			caption_offset_y: 0,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:00:00.000Z',
			expires_at: '2099-01-01T00:00:00.000Z',
		});
		recipientOne.dobj.bindings.USER_DO.get = vi.fn(() => sender.dobj);

		const sentOne: string[] = [];
		const wsOne = { send: (message: string) => sentOne.push(message) } as unknown as WebSocket;
		await handleClientMessage(recipientOne.dobj, wsOne, { type: 'view_snap', snap_id: 'snap-view-one' });
		await handleClientMessage(recipientOne.dobj, wsOne, { type: 'snap_viewed', snap_id: 'snap-view-one' });

		expect(downloadMock).toHaveBeenCalledWith(expect.anything(), expect.anything(), 'snaps/a/upload-shared-1', 3600);
		expect(JSON.parse(sentOne[0])).toEqual(
			expect.objectContaining({
				type: 'snap_download_url',
				snap_id: 'snap-view-one',
				upload_id: 'upload-shared-1',
				expires_in: 3600,
				media_width: 0,
				media_height: 0,
			})
		);
		expect(sender.state.storageValues.get('pending_snap_delete_deadlines')).toBeUndefined();
		expect(sender.state.alarmAt).toBeNull();

		const recipientTwo = createStatefulUserDo({
			uuid: RECIPIENT_TWO_UUID,
			profile: { uuid: RECIPIENT_TWO_UUID, username: 'RecipientTwo', skin_url: null },
		});
		recipientTwo.state.receivedSnaps.push({
			snap_id: 'snap-view-two',
			upload_id: 'upload-shared-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'image',
			r2_key: 'snaps/a/upload-shared-1',
			sent_at: '2026-03-25T00:05:00.000Z',
			viewed_at: null,
		});
		recipientTwo.state.snapMetadata.push({
			upload_id: 'upload-shared-1',
			from_uuid: SENDER_UUID,
			media_type: 'image',
			content_type: 'image/png',
			media_size: 1,
			caption_text: 'shared caption',
			caption_offset_y: 0,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:00:00.000Z',
			expires_at: '2099-01-01T00:00:00.000Z',
		});
		recipientTwo.dobj.bindings.USER_DO.get = vi.fn(() => sender.dobj);

		const sentTwo: string[] = [];
		const wsTwo = { send: (message: string) => sentTwo.push(message) } as unknown as WebSocket;
		await handleClientMessage(recipientTwo.dobj, wsTwo, { type: 'view_snap', snap_id: 'snap-view-two' });
		await handleClientMessage(recipientTwo.dobj, wsTwo, { type: 'snap_viewed', snap_id: 'snap-view-two' });

		expect(JSON.parse(sentTwo[0])).toEqual(
			expect.objectContaining({
				type: 'snap_download_url',
				snap_id: 'snap-view-two',
				upload_id: 'upload-shared-1',
				expires_in: 3600,
				media_width: 0,
				media_height: 0,
			})
		);
		expect(deleteMock).toHaveBeenCalledTimes(1);
		expect(deleteMock).toHaveBeenCalledWith('snaps/a/upload-shared-1');
		expect(sender.state.storageValues.get('pending_snap_delete_deadlines')).toBeUndefined();
		expect(sender.state.alarmAt).toBeNull();
	});

	it('keeps the shared R2 object alive until the last delivered recipient views it, then deletes immediately', async () => {
		vi.spyOn(presign, 'generateDownloadUrl').mockResolvedValue('https://download.example/snap');
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);
		const deleteMock = vi.fn(async () => undefined);

		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [acceptedFriend(RECIPIENT_UUID, 'TargetPlayer', 'https://textures.minecraft.net/skin/target')],
		});
		sender.state.sentSnaps.push({
			snap_id: 'snap-alarm-1',
			upload_id: 'upload-alarm-1',
			to_uuid: RECIPIENT_UUID,
			to_username: 'RecipientPlayer',
			to_skin_url: null,
			media_type: 'image',
			r2_key: 'snaps/a/upload-alarm-1',
			content_type: 'image/png',
			media_size: 1,
			sent_at: '2026-03-25T00:00:00.000Z',
			delivered_at: '2026-03-25T00:01:00.000Z',
			dropped_at: null,
			opened_at: null,
		});
		sender.dobj.bindings.SNAPS_BUCKET = { delete: deleteMock } as unknown as R2Bucket;

		const recipient = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
		});
		recipient.state.receivedSnaps.push({
			snap_id: 'snap-alarm-1',
			upload_id: 'upload-alarm-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: null,
			media_type: 'image',
			r2_key: 'snaps/a/upload-alarm-1',
			sent_at: '2026-03-25T00:10:00.000Z',
			viewed_at: null,
		});
		recipient.state.snapMetadata.push({
			upload_id: 'upload-alarm-1',
			from_uuid: SENDER_UUID,
			media_type: 'image',
			content_type: 'image/png',
			media_size: 1,
			caption_text: '',
			caption_offset_y: 0,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:10:00.000Z',
			expires_at: '2099-01-01T00:00:00.000Z',
		});
		recipient.dobj.bindings.USER_DO.get = vi.fn(() => sender.dobj);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(recipient.dobj, ws, { type: 'view_snap', snap_id: 'snap-alarm-1' });
		await handleClientMessage(recipient.dobj, ws, { type: 'snap_viewed', snap_id: 'snap-alarm-1' });

		expect(deleteMock).toHaveBeenCalledTimes(1);
		expect(deleteMock).toHaveBeenCalledWith('snaps/a/upload-alarm-1');
		expect(sender.state.storageValues.get('pending_snap_delete_deadlines')).toBeUndefined();
		expect(sender.state.alarmAt).toBeNull();
		await sender.dobj.alarm();
		expect(deleteMock).toHaveBeenCalledTimes(1);
	});

	it('returns sender unread sequencing metadata in snap_list for oldest-first sender playback', async () => {
		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
		});
		dobj.state.receivedSnaps.push(
			{
				snap_id: 'sender-a-1',
				upload_id: 'upload-a-1',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: 'https://textures.minecraft.net/skin/sender',
				media_type: 'image',
				r2_key: 'snaps/a/upload-a-1',
				sent_at: '2026-03-25T00:01:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'sender-a-2',
				upload_id: 'upload-a-2',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: 'https://textures.minecraft.net/skin/sender',
				media_type: 'video',
				r2_key: 'snaps/a/upload-a-2',
				sent_at: '2026-03-25T00:02:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'sender-a-3',
				upload_id: 'upload-a-3',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: 'https://textures.minecraft.net/skin/sender',
				media_type: 'image',
				r2_key: 'snaps/a/upload-a-3',
				sent_at: '2026-03-25T00:03:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'sender-b-1',
				upload_id: 'upload-b-1',
				from_uuid: RECIPIENT_TWO_UUID,
				from_username: 'SecondSender',
				from_skin_url: 'https://textures.minecraft.net/skin/second',
				media_type: 'image',
				r2_key: 'snaps/b/upload-b-1',
				sent_at: '2026-03-25T00:00:30.000Z',
				viewed_at: null,
			}
		);
		dobj.state.snapMetadata.push(
			{
				upload_id: 'upload-a-1',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 1,
				caption_text: 'a1',
				caption_offset_y: 0.1,
				expiry_ms: 86_400_000,
				sent_at: '2026-03-25T00:01:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-a-2',
				from_uuid: SENDER_UUID,
				media_type: 'video',
				content_type: 'video/mp4',
				media_size: 2,
				caption_text: 'a2',
				caption_offset_y: 0.2,
				expiry_ms: 86_400_000,
				sent_at: '2026-03-25T00:02:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-a-3',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 3,
				caption_text: 'a3',
				caption_offset_y: 0.3,
				expiry_ms: 86_400_000,
				sent_at: '2026-03-25T00:03:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-b-1',
				from_uuid: RECIPIENT_TWO_UUID,
				media_type: 'image',
				content_type: 'image/jpeg',
				media_size: 4,
				caption_text: 'b1',
				caption_offset_y: -0.2,
				expiry_ms: 86_400_000,
				sent_at: '2026-03-25T00:00:30.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_snaps' });

		const payload = JSON.parse(sent[0]);
		expect(payload.type).toBe('snap_list');
		expect(payload.snaps).toHaveLength(4);
		const senderA = payload.snaps.filter((snap: { from: string }) => snap.from === SENDER_UUID);
		expect(senderA).toEqual([
			expect.objectContaining({
				snap_id: 'sender-a-1',
				sender_unread_position: 1,
				sender_unread_total: 3,
				sender_oldest_unread_snap_id: 'sender-a-1',
				sender_next_unread_snap_id: 'sender-a-2',
				content_type: 'image/png',
				media_width: 0,
				media_height: 0,
				caption_text: 'a1',
				caption_offset_y: 0.1,
			}),
			expect.objectContaining({
				snap_id: 'sender-a-2',
				sender_unread_position: 2,
				sender_unread_total: 3,
				sender_oldest_unread_snap_id: 'sender-a-1',
				sender_next_unread_snap_id: 'sender-a-3',
				content_type: 'video/mp4',
				media_width: 0,
				media_height: 0,
				caption_text: 'a2',
				caption_offset_y: 0.2,
			}),
			expect.objectContaining({
				snap_id: 'sender-a-3',
				sender_unread_position: 3,
				sender_unread_total: 3,
				sender_oldest_unread_snap_id: 'sender-a-1',
				sender_next_unread_snap_id: null,
				content_type: 'image/png',
				media_width: 0,
				media_height: 0,
				caption_text: 'a3',
				caption_offset_y: 0.3,
			}),
		]);
	});

	it('marks expired snaps unavailable in view_snap and returns snap_expired', async () => {
		vi.spyOn(presign, 'createR2Client').mockReturnValue({} as never);
		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
		});
		dobj.state.receivedSnaps.push({
			snap_id: 'snap-expired-1',
			upload_id: 'upload-expired-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: null,
			media_type: 'image',
			r2_key: 'snaps/a/upload-expired-1',
			sent_at: '2026-03-25T00:10:00.000Z',
			viewed_at: null,
		});
		dobj.state.snapMetadata.push({
			upload_id: 'upload-expired-1',
			from_uuid: SENDER_UUID,
			media_type: 'image',
			content_type: 'image/png',
			media_size: 1,
			caption_text: '',
			caption_offset_y: 0,
			expiry_ms: 3_600_000,
			sent_at: '2026-03-25T00:10:00.000Z',
			expires_at: '2000-01-01T00:00:00.000Z',
		});

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'view_snap', snap_id: 'snap-expired-1' });

		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'snap_expired',
			message: 'Snap has expired',
		});
		expect(dobj.state.receivedSnaps[0].viewed_at).toBe('2026-03-25T00:03:00.000Z');
	});

	it('reconciles expired unread recipient rows when building get_snaps', async () => {
		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
		});
		dobj.state.receivedSnaps.push(
			{
				snap_id: 'snap-expired-list',
				upload_id: 'upload-expired-list',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/z/expired',
				sent_at: '2026-03-25T00:00:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'snap-live-list',
				upload_id: 'upload-live-list',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: null,
				media_type: 'video',
				r2_key: 'snaps/z/live',
				sent_at: '2026-03-25T00:01:00.000Z',
				viewed_at: null,
			}
		);
		dobj.state.snapMetadata.push(
			{
				upload_id: 'upload-expired-list',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:00:00.000Z',
				expires_at: '2000-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-live-list',
				from_uuid: SENDER_UUID,
				media_type: 'video',
				content_type: 'video/mp4',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:01:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_snaps' });

		const payload = JSON.parse(sent[0]);
		expect(payload.type).toBe('snap_list');
		expect(payload.snaps).toHaveLength(1);
		expect(payload.snaps[0]).toEqual(expect.objectContaining({ snap_id: 'snap-live-list' }));
		expect(
			dobj.state.receivedSnaps.find((snap) => snap.snap_id === 'snap-expired-list')?.viewed_at
		).toBe('2026-03-25T00:03:00.000Z');
	});

	it('returns merged chat recents from sent and received history', async () => {
		const dobj = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
		});
		dobj.state.sentSnaps.push({
			snap_id: 'snap-sent-1',
			upload_id: 'upload-sent-1',
			to_uuid: RECIPIENT_UUID,
			to_username: 'RecentTarget',
			to_skin_url: 'https://textures.minecraft.net/skin/recent',
			media_type: 'image',
			r2_key: 'snaps/x/upload-sent-1',
			content_type: 'image/png',
			media_size: 123,
			sent_at: '2026-03-25T00:00:00.000Z',
			delivered_at: '2026-03-25T00:01:00.000Z',
			dropped_at: null,
			opened_at: null,
		});
		dobj.state.receivedSnaps.push(
			{
				snap_id: 'snap-recv-1',
				from_uuid: RECIPIENT_TWO_UUID,
				from_username: 'IncomingTarget',
				from_skin_url: 'https://textures.minecraft.net/skin/incoming',
				media_type: 'video',
				r2_key: 'snaps/y/recv-1',
				sent_at: '2026-03-25T00:03:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'snap-recv-2',
				from_uuid: RECIPIENT_TWO_UUID,
				from_username: 'IncomingTarget',
				from_skin_url: 'https://textures.minecraft.net/skin/incoming',
				media_type: 'image',
				r2_key: 'snaps/y/recv-2',
				sent_at: '2026-03-25T00:04:00.000Z',
				viewed_at: null,
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_chat_recents' });

		expect(JSON.parse(sent[0])).toEqual({
			type: 'chat_recents',
			recents: [
				{
					uuid: RECIPIENT_TWO_UUID,
					username: 'IncomingTarget',
					skin_url: 'https://textures.minecraft.net/skin/incoming',
					last_direction: 'received',
					last_media_type: 'image',
					last_timestamp: '2026-03-25T00:04:00.000Z',
					last_activity_type: 'received',
					last_activity_timestamp: '2026-03-25T00:04:00.000Z',
					incoming_unopened_count: 2,
					incoming_unopened_media_type: 'image',
					incoming_unopened_timestamp: '2026-03-25T00:04:00.000Z',
					outgoing_unopened_count: 0,
					outgoing_unopened_media_type: null,
					outgoing_unopened_timestamp: null,
				},
				{
					uuid: RECIPIENT_UUID,
					username: 'RecentTarget',
					skin_url: 'https://textures.minecraft.net/skin/recent',
					last_direction: 'sent',
					last_media_type: 'image',
					last_timestamp: '2026-03-25T00:00:00.000Z',
					last_activity_type: 'sent',
					last_activity_timestamp: '2026-03-25T00:00:00.000Z',
					incoming_unopened_count: 0,
					incoming_unopened_media_type: null,
					incoming_unopened_timestamp: null,
					outgoing_unopened_count: 1,
					outgoing_unopened_media_type: 'image',
					outgoing_unopened_timestamp: '2026-03-25T00:00:00.000Z',
				},
			],
		});
	});

	it('excludes expired unread incoming snaps from chat_recents and reconciles them as viewed', async () => {
		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'RecipientPlayer', skin_url: null },
		});
		dobj.state.receivedSnaps.push(
			{
				snap_id: 'snap-recv-expired',
				upload_id: 'upload-recv-expired',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/y/recv-expired',
				sent_at: '2026-03-25T00:01:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'snap-recv-live',
				upload_id: 'upload-recv-live',
				from_uuid: SENDER_UUID,
				from_username: 'SenderPlayer',
				from_skin_url: null,
				media_type: 'video',
				r2_key: 'snaps/y/recv-live',
				sent_at: '2026-03-25T00:02:00.000Z',
				viewed_at: null,
			}
		);
		dobj.state.snapMetadata.push(
			{
				upload_id: 'upload-recv-expired',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:01:00.000Z',
				expires_at: '2000-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-recv-live',
				from_uuid: SENDER_UUID,
				media_type: 'video',
				content_type: 'video/mp4',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:02:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_chat_recents' });

		const payload = JSON.parse(sent[0]);
		expect(payload.type).toBe('chat_recents');
		expect(payload.recents).toEqual([
			expect.objectContaining({
				uuid: SENDER_UUID,
				incoming_unopened_count: 1,
				incoming_unopened_media_type: 'video',
				incoming_unopened_timestamp: '2026-03-25T00:02:00.000Z',
			}),
		]);
		expect(
			dobj.state.receivedSnaps.find((snap) => snap.snap_id === 'snap-recv-expired')?.viewed_at
		).toBe('2026-03-25T00:03:00.000Z');
	});

	it('reports separate incoming and outgoing unopened status summaries for chat_recents', async () => {
		const dobj = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
		});
		dobj.state.sentSnaps.push(
			{
				snap_id: 'snap-sent-dropped',
				upload_id: 'upload-dropped',
				to_uuid: RECIPIENT_UUID,
				to_username: 'PriorityTarget',
				to_skin_url: 'https://textures.minecraft.net/skin/priority',
				media_type: 'image',
				r2_key: 'snaps/x/upload-dropped',
				content_type: 'image/png',
				media_size: 123,
				sent_at: '2026-03-25T00:06:00.000Z',
				delivered_at: null,
				dropped_at: '2026-03-25T00:06:01.000Z',
				opened_at: null,
			},
			{
				snap_id: 'snap-sent-pending',
				upload_id: 'upload-pending',
				to_uuid: RECIPIENT_UUID,
				to_username: 'PriorityTarget',
				to_skin_url: 'https://textures.minecraft.net/skin/priority',
				media_type: 'video',
				r2_key: 'snaps/x/upload-pending',
				content_type: 'video/mp4',
				media_size: 321,
				sent_at: '2026-03-25T00:05:00.000Z',
				delivered_at: '2026-03-25T00:05:10.000Z',
				dropped_at: null,
				opened_at: null,
			},
			{
				snap_id: 'snap-sent-opened',
				upload_id: 'upload-opened',
				to_uuid: RECIPIENT_UUID,
				to_username: 'PriorityTarget',
				to_skin_url: 'https://textures.minecraft.net/skin/priority',
				media_type: 'image',
				r2_key: 'snaps/x/upload-opened',
				content_type: 'image/png',
				media_size: 123,
				sent_at: '2026-03-25T00:04:00.000Z',
				delivered_at: '2026-03-25T00:04:10.000Z',
				dropped_at: null,
				opened_at: '2026-03-25T00:04:30.000Z',
			}
		);
		dobj.state.receivedSnaps.push(
			{
				snap_id: 'snap-recv-unread',
				from_uuid: RECIPIENT_UUID,
				from_username: 'PriorityTarget',
				from_skin_url: 'https://textures.minecraft.net/skin/priority',
				media_type: 'image',
				r2_key: 'snaps/y/recv-unread',
				sent_at: '2026-03-25T00:03:00.000Z',
				viewed_at: null,
			},
			{
				snap_id: 'snap-recv-read',
				from_uuid: RECIPIENT_UUID,
				from_username: 'PriorityTarget',
				from_skin_url: 'https://textures.minecraft.net/skin/priority',
				media_type: 'video',
				r2_key: 'snaps/y/recv-read',
				sent_at: '2026-03-25T00:02:00.000Z',
				viewed_at: '2026-03-25T00:02:30.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_chat_recents' });

		expect(JSON.parse(sent[0])).toEqual({
			type: 'chat_recents',
			recents: [
				{
					uuid: RECIPIENT_UUID,
					username: 'PriorityTarget',
					skin_url: 'https://textures.minecraft.net/skin/priority',
					last_direction: 'sent',
					last_media_type: 'image',
					last_timestamp: '2026-03-25T00:06:00.000Z',
					last_activity_type: 'dropped',
					last_activity_timestamp: '2026-03-25T00:06:01.000Z',
					incoming_unopened_count: 1,
					incoming_unopened_media_type: 'image',
					incoming_unopened_timestamp: '2026-03-25T00:03:00.000Z',
					outgoing_unopened_count: 1,
					outgoing_unopened_media_type: 'video',
					outgoing_unopened_timestamp: '2026-03-25T00:05:00.000Z',
				},
			],
		});
	});

	it('excludes expired unopened outgoing snaps from chat_recents and reconciles them as dropped', async () => {
		const dobj = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		dobj.state.sentSnaps.push(
			{
				snap_id: 'snap-sent-expired',
				upload_id: 'upload-sent-expired',
				to_uuid: RECIPIENT_UUID,
				to_username: 'RecipientPlayer',
				to_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/x/sent-expired',
				content_type: 'image/png',
				media_size: 1,
				sent_at: '2026-03-25T00:05:00.000Z',
				delivered_at: '2026-03-25T00:05:10.000Z',
				dropped_at: null,
				opened_at: null,
			},
			{
				snap_id: 'snap-sent-live',
				upload_id: 'upload-sent-live',
				to_uuid: RECIPIENT_UUID,
				to_username: 'RecipientPlayer',
				to_skin_url: null,
				media_type: 'video',
				r2_key: 'snaps/x/sent-live',
				content_type: 'video/mp4',
				media_size: 1,
				sent_at: '2026-03-25T00:06:00.000Z',
				delivered_at: '2026-03-25T00:06:10.000Z',
				dropped_at: null,
				opened_at: null,
			}
		);
		dobj.state.snapMetadata.push(
			{
				upload_id: 'upload-sent-expired',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:05:00.000Z',
				expires_at: '2000-01-01T00:00:00.000Z',
			},
			{
				upload_id: 'upload-sent-live',
				from_uuid: SENDER_UUID,
				media_type: 'video',
				content_type: 'video/mp4',
				media_size: 1,
				caption_text: '',
				caption_offset_y: 0,
				expiry_ms: 3_600_000,
				sent_at: '2026-03-25T00:06:00.000Z',
				expires_at: '2099-01-01T00:00:00.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_chat_recents' });

		const payload = JSON.parse(sent[0]);
		expect(payload.type).toBe('chat_recents');
		expect(payload.recents).toEqual([
			expect.objectContaining({
				uuid: RECIPIENT_UUID,
				outgoing_unopened_count: 1,
				outgoing_unopened_media_type: 'video',
				outgoing_unopened_timestamp: '2026-03-25T00:06:00.000Z',
			}),
		]);
		expect(
			dobj.state.sentSnaps.find((snap) => snap.snap_id === 'snap-sent-expired')?.dropped_at
		).toBe('2026-03-25T00:01:00.000Z');
	});

	it('sorts chat_recents by latest activity timestamp including opened events', async () => {
		const dobj = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		dobj.state.sentSnaps.push(
			{
				snap_id: 'snap-a-sent',
				upload_id: 'upload-a',
				to_uuid: RECIPIENT_UUID,
				to_username: 'A',
				to_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/a',
				content_type: 'image/png',
				media_size: 1,
				sent_at: '2026-03-25T00:10:00.000Z',
				delivered_at: '2026-03-25T00:10:10.000Z',
				dropped_at: null,
				opened_at: null,
			},
			{
				snap_id: 'snap-b-sent',
				upload_id: 'upload-b',
				to_uuid: RECIPIENT_TWO_UUID,
				to_username: 'B',
				to_skin_url: null,
				media_type: 'image',
				r2_key: 'snaps/b',
				content_type: 'image/png',
				media_size: 1,
				sent_at: '2026-03-25T00:09:00.000Z',
				delivered_at: '2026-03-25T00:09:10.000Z',
				dropped_at: null,
				opened_at: '2026-03-25T00:11:00.000Z',
			}
		);

		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		await handleClientMessage(dobj.dobj, ws, { type: 'get_chat_recents' });

		const payload = JSON.parse(sent[0]);
		expect(payload.type).toBe('chat_recents');
		expect(payload.recents.map((entry: { uuid: string }) => entry.uuid)).toEqual([RECIPIENT_TWO_UUID, RECIPIENT_UUID]);
		expect(payload.recents[0]).toMatchObject({
			uuid: RECIPIENT_TWO_UUID,
			last_activity_type: 'opened',
			last_activity_timestamp: '2026-03-25T00:11:00.000Z',
		});
	});

	it('rejects send_snap when more than ten unique recipients are selected', async () => {
		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: null },
		});
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;

		await handleClientMessage(sender.dobj, ws, {
			type: 'send_snap',
			to: [
				'00000000000000000000000000000001',
				'00000000000000000000000000000002',
				'00000000000000000000000000000003',
				'00000000000000000000000000000004',
				'00000000000000000000000000000005',
				'00000000000000000000000000000006',
				'00000000000000000000000000000007',
				'00000000000000000000000000000008',
				'00000000000000000000000000000009',
				'0000000000000000000000000000000a',
				'0000000000000000000000000000000b',
			],
			media_type: 'image',
			media_size: 123,
			content_type: 'image/png',
		});

		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'too_many_recipients',
			message: 'You can only send to 10 recipients at once',
		});
	});

	it('keeps social state when createSession refreshes a bootstrapped DO profile', async () => {
		const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
			new Response(
				JSON.stringify({
					id: RECIPIENT_UUID,
					name: BOOTSTRAPPED_USERNAME,
					properties: [
						{
							name: 'textures',
							value: btoa(JSON.stringify({ textures: { SKIN: { url: BOOTSTRAPPED_SKIN } } })),
						},
					],
				}),
				{ status: 200 }
			)
		);

		const recipient = createStatefulUserDo({ uuid: RECIPIENT_UUID });
		// tryFriendRequest bootstraps the profile and records the friend in one call.
		await recipient.dobj.tryFriendRequest(SENDER_UUID, 'SenderPlayer', null);

		await recipient.dobj.createSession('session-token', 'VerifiedPlayer', 'https://textures.minecraft.net/skin/verified');

		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(recipient.state.profile).toEqual({
			uuid: RECIPIENT_UUID,
			username: 'VerifiedPlayer',
			skin_url: 'https://textures.minecraft.net/skin/verified',
		});
		expect(recipient.state.friends).toEqual([
			{
				friend_uuid: SENDER_UUID,
				friend_username: 'SenderPlayer',
				friend_skin_url: null,
				status: 'pending_incoming',
			},
		]);
	});

	it('stores default expiry updates via the generic preference flow', async () => {
		const exec = vi.fn();
		const sent: string[] = [];
		const dobj = {
			sql: { exec },
		} as unknown as BlockChatUserDurableObject;
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;

		await handleClientMessage(dobj, ws, {
			type: 'set_preference',
			key: 'default_expiry_ms',
			value: 604800000,
		});

		expect(exec).toHaveBeenCalledWith(
			expect.stringContaining('INSERT INTO preferences'),
			'default_expiry_ms',
			'86400000'
		);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'preference_set',
			key: 'default_expiry_ms',
			value: 86400000,
		});
	});

	it('clamps stored default expiry back to one day in preference snapshots', () => {
		const dobj = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			preferences: { default_expiry_ms: 604800000 },
		});

		expect(dobj.dobj.getPreferencesSnapshot().default_expiry_ms).toBe(86_400_000);
	});

	it('blocks friend requests when the recipient has friend requests disabled', async () => {
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const insertCalls: Array<unknown[]> = [];
		const idFromName = vi.fn(() => 'recipient-id');
		const recipientStub = {
			tryFriendRequest: async () => ({ status: 'disabled' as const }),
		};
		const dobj = {
			sql: {
				exec: (query: string, ...params: unknown[]) => {
					if (query.includes('SELECT uuid, username, skin_url FROM profile LIMIT 1')) {
						return { one: () => ({ uuid: 'sender-uuid', username: 'SenderPlayer', skin_url: null }) };
					}
					if (query.includes('SELECT status FROM friends WHERE friend_uuid = ?')) {
						return { toArray: () => [] };
					}
					if (query.includes('INSERT INTO friends')) {
						insertCalls.push([query, ...params]);
						return { toArray: () => [] };
					}
					throw new Error(`Unexpected SQL: ${query}`);
				},
			},
			bindings: {
				USER_DO: {
					idFromName,
					get: () => recipientStub,
				},
			},
		} as unknown as BlockChatUserDurableObject;

		await handleClientMessage(dobj, ws, { type: 'add_friend', uuid: 'Recipient-UUID' });

		expect(idFromName).toHaveBeenCalledWith('recipientuuid');
		expect(insertCalls).toHaveLength(0);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'error',
			code: 'friend_requests_disabled',
			message: 'This user is not accepting friend requests',
		});
	});

	it('includes the sender skin URL in friend request websocket notifications', async () => {
		const sent: string[] = [];
		const ws = { send: (message: string) => sent.push(message) } as unknown as WebSocket;
		const insertCalls: Array<unknown[]> = [];
		const dobj = Object.create(BlockChatUserDurableObject.prototype) as BlockChatUserDurableObject;
		dobj.sql = {
			exec: (query: string, ...params: unknown[]) => {
				if (query.includes('SELECT username, skin_url FROM profile LIMIT 1')) {
					return { toArray: () => [{ username: 'TargetPlayer', skin_url: null }] };
				}
				if (query.includes('SELECT value FROM preferences WHERE key = ?') && params[0] === 'friend_requests') {
					return { toArray: () => [{ value: '1' }] };
				}
				if (query.includes('SELECT status FROM friends WHERE friend_uuid = ?')) {
					return { toArray: () => [] };
				}
				if (query.includes('INSERT INTO friends')) {
					insertCalls.push([query, ...params]);
					return { toArray: () => [] };
				}
				throw new Error(`Unexpected SQL: ${query}`);
			},
		} as unknown as SqlStorage;
		dobj.ctx = {
			getWebSockets: () => [ws],
		} as DurableObjectState;

		const result = await dobj.tryFriendRequest('Requester-UUID', 'Requester', 'https://textures.minecraft.net/skin');

		expect(result).toEqual({
			status: 'sent',
			username: 'TargetPlayer',
			skin_url: null,
		});
		expect(insertCalls).toHaveLength(1);
		expect(insertCalls[0]).toEqual([
			expect.stringContaining('INSERT INTO friends'),
			'requesteruuid',
			'Requester',
			'https://textures.minecraft.net/skin',
		]);
		expect(sent).toHaveLength(1);
		expect(JSON.parse(sent[0])).toEqual({
			type: 'friend_request',
			from: 'requesteruuid',
			from_username: 'Requester',
			from_skin_url: 'https://textures.minecraft.net/skin',
		});
	});

	it('handles delete_account as a full reset with peer purge, websocket shutdown, and bucket cleanup', async () => {
		const peerOne = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: { uuid: RECIPIENT_UUID, username: 'PeerOne', skin_url: 'https://textures.minecraft.net/skin/one' },
			friends: [
				{
					friend_uuid: SENDER_UUID,
					friend_username: 'SenderPlayer',
					friend_skin_url: 'https://textures.minecraft.net/skin/sender',
					status: 'accepted',
				},
			],
		});
		peerOne.state.receivedSnaps.push({
			snap_id: 'peer-one-received',
			upload_id: 'sender-upload-1',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'image',
			r2_key: 'snaps/sender/sender-upload-1',
			sent_at: '2026-03-25T00:00:00.000Z',
			viewed_at: null,
		});
		peerOne.state.sentSnaps.push({
			snap_id: 'peer-one-sent-to-deleted',
			upload_id: 'peer-one-upload',
			to_uuid: SENDER_UUID,
			to_username: 'SenderPlayer',
			to_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'video',
			r2_key: 'snaps/peer-one/peer-one-upload',
			content_type: 'video/mp4',
			media_size: 55,
			sent_at: '2026-03-25T00:02:00.000Z',
			delivered_at: null,
			dropped_at: null,
			opened_at: null,
		});
		peerOne.state.snapMetadata.push(
			{
				upload_id: 'sender-upload-1',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 10,
				media_width: 16,
				media_height: 16,
				caption_text: '',
				caption_offset_y: 0,
					expiry_ms: 86_400_000,
					sent_at: '2026-03-25T00:00:00.000Z',
					expires_at: '2026-03-29T00:00:00.000Z',
				},
				{
					upload_id: 'peer-one-upload',
				from_uuid: RECIPIENT_UUID,
				media_type: 'video',
				content_type: 'video/mp4',
				media_size: 55,
				media_width: 1920,
				media_height: 1080,
				caption_text: '',
				caption_offset_y: 0,
					expiry_ms: 86_400_000,
					sent_at: '2026-03-25T00:02:00.000Z',
					expires_at: '2026-03-29T00:02:00.000Z',
				}
			);
		const peerTwo = createStatefulUserDo({
			uuid: RECIPIENT_TWO_UUID,
			profile: { uuid: RECIPIENT_TWO_UUID, username: 'PeerTwo', skin_url: 'https://textures.minecraft.net/skin/two' },
			friends: [
				{
					friend_uuid: SENDER_UUID,
					friend_username: 'SenderPlayer',
					friend_skin_url: 'https://textures.minecraft.net/skin/sender',
					status: 'accepted',
				},
			],
		});
		peerTwo.state.receivedSnaps.push({
			snap_id: 'peer-two-received',
			upload_id: 'sender-upload-2',
			from_uuid: SENDER_UUID,
			from_username: 'SenderPlayer',
			from_skin_url: 'https://textures.minecraft.net/skin/sender',
			media_type: 'image',
			r2_key: 'snaps/sender/sender-upload-2',
			sent_at: '2026-03-25T00:03:00.000Z',
			viewed_at: null,
		});
		peerTwo.state.snapMetadata.push({
			upload_id: 'sender-upload-2',
			from_uuid: SENDER_UUID,
			media_type: 'image',
			content_type: 'image/png',
			media_size: 10,
			media_width: 16,
			media_height: 16,
			caption_text: '',
			caption_offset_y: 0,
			expiry_ms: 86_400_000,
			sent_at: '2026-03-25T00:03:00.000Z',
			expires_at: '2026-03-29T00:03:00.000Z',
		});

		const sender = createStatefulUserDo({
			uuid: SENDER_UUID,
			profile: { uuid: SENDER_UUID, username: 'SenderPlayer', skin_url: 'https://textures.minecraft.net/skin/sender' },
			friends: [
				{
					friend_uuid: RECIPIENT_UUID,
					friend_username: 'PeerOne',
					friend_skin_url: 'https://textures.minecraft.net/skin/one',
					status: 'accepted',
				},
				{
					friend_uuid: RECIPIENT_TWO_UUID,
					friend_username: 'PeerTwo',
					friend_skin_url: 'https://textures.minecraft.net/skin/two',
					status: 'pending_incoming',
				},
			],
		});
		sender.state.receivedSnaps.push({
			snap_id: 'sender-received',
			upload_id: 'peer-upload',
			from_uuid: RECIPIENT_UUID,
			from_username: 'PeerOne',
			from_skin_url: 'https://textures.minecraft.net/skin/one',
			media_type: 'image',
			r2_key: 'snaps/peer-one/peer-upload',
			sent_at: '2026-03-25T00:01:00.000Z',
			viewed_at: null,
		});
		sender.state.sentSnaps.push(
			{
				snap_id: 'sender-sent-1',
				upload_id: 'sender-upload-1',
				to_uuid: RECIPIENT_UUID,
				to_username: 'PeerOne',
				to_skin_url: 'https://textures.minecraft.net/skin/one',
				media_type: 'image',
				r2_key: 'snaps/sender/sender-upload-1',
				content_type: 'image/png',
				media_size: 10,
				sent_at: '2026-03-25T00:00:00.000Z',
				delivered_at: null,
				dropped_at: null,
				opened_at: null,
			},
			{
				snap_id: 'sender-sent-2',
				upload_id: 'sender-upload-2',
				to_uuid: RECIPIENT_TWO_UUID,
				to_username: 'PeerTwo',
				to_skin_url: 'https://textures.minecraft.net/skin/two',
				media_type: 'image',
				r2_key: 'snaps/sender/sender-upload-2',
				content_type: 'image/png',
				media_size: 10,
				sent_at: '2026-03-25T00:03:00.000Z',
				delivered_at: null,
				dropped_at: null,
				opened_at: null,
			}
		);
		sender.state.snapMetadata.push(
			{
				upload_id: 'sender-upload-1',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 10,
				media_width: 16,
				media_height: 16,
				caption_text: '',
				caption_offset_y: 0,
					expiry_ms: 86_400_000,
					sent_at: '2026-03-25T00:00:00.000Z',
					expires_at: '2026-03-29T00:00:00.000Z',
				},
				{
					upload_id: 'sender-upload-2',
				from_uuid: SENDER_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 10,
				media_width: 16,
				media_height: 16,
				caption_text: '',
				caption_offset_y: 0,
					expiry_ms: 86_400_000,
					sent_at: '2026-03-25T00:03:00.000Z',
					expires_at: '2026-03-29T00:03:00.000Z',
				},
				{
					upload_id: 'peer-upload',
				from_uuid: RECIPIENT_UUID,
				media_type: 'image',
				content_type: 'image/png',
				media_size: 10,
				media_width: 16,
				media_height: 16,
				caption_text: '',
				caption_offset_y: 0,
					expiry_ms: 86_400_000,
					sent_at: '2026-03-25T00:01:00.000Z',
					expires_at: '2026-03-29T00:01:00.000Z',
				}
			);

		const senderBucketDelete = vi.fn(async () => undefined);
		const peerOneBucketDelete = vi.fn(async () => undefined);
		const peerTwoBucketDelete = vi.fn(async () => undefined);
		sender.dobj.bindings.SNAPS_BUCKET = { delete: senderBucketDelete } as unknown as R2Bucket;
		peerOne.dobj.bindings.SNAPS_BUCKET = { delete: peerOneBucketDelete } as unknown as R2Bucket;
		peerTwo.dobj.bindings.SNAPS_BUCKET = { delete: peerTwoBucketDelete } as unknown as R2Bucket;
		sender.dobj.bindings.USER_DO.get = vi.fn((id: { name: string }) => {
			if (id.name === RECIPIENT_UUID) {
				return peerOne.dobj;
			}
			if (id.name === RECIPIENT_TWO_UUID) {
				return peerTwo.dobj;
			}
			throw new Error(`Unexpected DO lookup: ${id.name}`);
		});

		const socketOneSent: string[] = [];
		const socketTwoSent: string[] = [];
		const socketOneClose = vi.fn();
		const socketTwoClose = vi.fn();
		const socketOne = {
			send: vi.fn((message: string) => socketOneSent.push(message)),
			close: socketOneClose,
		} as unknown as WebSocket;
		const socketTwo = {
			send: vi.fn((message: string) => socketTwoSent.push(message)),
			close: socketTwoClose,
		} as unknown as WebSocket;
		sender.dobj.ctx = {
			...sender.dobj.ctx,
			getWebSockets: () => [socketOne, socketTwo],
		} as DurableObjectState;
		Object.assign(sender.dobj, {
			recentUserSearches: [1, 2, 3],
			uploadTimestamps: [4, 5],
		});

		const peerOnePushes: string[] = [];
		const peerTwoPushes: string[] = [];
		peerOne.dobj.ctx = {
			...peerOne.dobj.ctx,
			getWebSockets: () => [{ send: (message: string) => peerOnePushes.push(message) } as unknown as WebSocket],
		} as DurableObjectState;
		peerTwo.dobj.ctx = {
			...peerTwo.dobj.ctx,
			getWebSockets: () => [{ send: (message: string) => peerTwoPushes.push(message) } as unknown as WebSocket],
		} as DurableObjectState;

		await handleClientMessage(sender.dobj, socketOne, { type: 'delete_account' });

		expect(socketOneSent).toEqual([JSON.stringify({ type: 'account_deleted' })]);
		expect(socketTwoSent).toEqual([JSON.stringify({ type: 'account_deleted' })]);
		expect(socketOneClose).toHaveBeenCalledWith(1000, 'Account deleted');
		expect(socketTwoClose).toHaveBeenCalledWith(1000, 'Account deleted');
		expect(senderBucketDelete).toHaveBeenCalledTimes(2);
		expect(senderBucketDelete).toHaveBeenCalledWith('snaps/sender/sender-upload-1');
		expect(senderBucketDelete).toHaveBeenCalledWith('snaps/sender/sender-upload-2');
		expect(peerOneBucketDelete).toHaveBeenCalledWith('snaps/peer-one/peer-one-upload');
		expect(peerTwoBucketDelete).not.toHaveBeenCalled();
		expect(peerOne.state.friends).toEqual([]);
		expect(peerOne.state.receivedSnaps).toEqual([]);
		expect(peerOne.state.sentSnaps).toEqual([]);
		expect(peerOne.state.snapMetadata).toEqual([]);
		expect(peerTwo.state.friends).toEqual([]);
		expect(peerTwo.state.receivedSnaps).toEqual([]);
		expect(peerTwo.state.snapMetadata).toEqual([]);
		expect(peerOnePushes.map((message) => JSON.parse(message).type)).toEqual(['friend_list', 'snap_list', 'chat_recents']);
		expect(peerTwoPushes.map((message) => JSON.parse(message).type)).toEqual(['friend_list', 'snap_list', 'chat_recents']);
		expect(sender.state.profile).toBeNull();
		expect(sender.state.friends).toEqual([]);
		expect(sender.state.receivedSnaps).toEqual([]);
		expect(sender.state.sentSnaps).toEqual([]);
		expect(sender.state.snapMetadata).toEqual([]);
		expect(sender.dobj.ctx.storage.deleteAlarm).toHaveBeenCalledTimes(1);
		expect(sender.dobj.ctx.storage.deleteAll).toHaveBeenCalledTimes(1);
		expect((sender.dobj as BlockChatUserDurableObject & { recentUserSearches: number[] }).recentUserSearches).toEqual([]);
		expect((sender.dobj as BlockChatUserDurableObject & { uploadTimestamps: number[] }).uploadTimestamps).toEqual([]);
	});

	it('recreates a blank schema after delete_account so future lazy bootstrap still works', async () => {
		const deletedUser = createStatefulUserDo({
			uuid: RECIPIENT_UUID,
			profile: {
				uuid: RECIPIENT_UUID,
				username: 'RecipientPlayer',
				skin_url: 'https://textures.minecraft.net/skin/recipient',
			},
		});
		deletedUser.dobj.bindings.SNAPS_BUCKET = { delete: vi.fn(async () => undefined) } as unknown as R2Bucket;
		deletedUser.dobj.bindings.USER_DO.get = vi.fn();

		const lookupSpy = vi.spyOn(helpers, 'fetchMojangPublicProfileByUuid').mockResolvedValue({
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
			skinUrl: BOOTSTRAPPED_SKIN,
		});

		await deletedUser.dobj.deleteAccount();
		const result = await deletedUser.dobj.tryFriendRequest(SENDER_UUID, 'Requester', null);

		expect(result).toEqual({
			status: 'sent',
			username: BOOTSTRAPPED_USERNAME,
			skin_url: BOOTSTRAPPED_SKIN,
		});
		expect(lookupSpy).toHaveBeenCalledWith(RECIPIENT_UUID);
		expect(deletedUser.state.profile).toEqual({
			uuid: RECIPIENT_UUID,
			username: BOOTSTRAPPED_USERNAME,
			skin_url: BOOTSTRAPPED_SKIN,
		});
		expect(deletedUser.state.friends).toEqual([
			{
				friend_uuid: SENDER_UUID,
				friend_username: 'Requester',
				friend_skin_url: null,
				status: 'pending_incoming',
			},
		]);
	});
});
