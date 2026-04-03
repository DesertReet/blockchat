import { DEFAULT_PREFERENCES } from '../ws-protocol';

const CURRENT_SCHEMA_VERSION = 6;

/**
 * Runs versioned schema migrations. On subsequent wakes when the schema is
 * already up-to-date this only executes one lightweight DDL no-op plus a
 * single row read, eliminating the per-wake cost of the full migration.
 *
 * **Adding columns:** bump `CURRENT_SCHEMA_VERSION`, add a `migrateToVn` that
 * uses `ALTER TABLE ... ADD COLUMN` (via `ensureColumn`) for new fields—do not
 * rely on editing `CREATE TABLE IF NOT EXISTS` alone; existing DO SQLite files
 * keep their old shape until altered. See AGENTS.md (Durable Object SQLite).
 */
export function migrateDatabase(sql: SqlStorage): void {
	sql.exec('CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)');
	const versionRows = sql.exec('SELECT version FROM schema_version LIMIT 1').toArray();
	const currentVersion = versionRows.length > 0 ? Number(versionRows[0].version) : 0;

	if (currentVersion >= CURRENT_SCHEMA_VERSION) return;

	if (currentVersion < 1) {
		migrateToV1(sql);
	}
	if (currentVersion < 2) {
		migrateToV2(sql);
	}
	if (currentVersion < 3) {
		migrateToV3(sql);
	}
	if (currentVersion < 4) {
		migrateToV4(sql);
	}
	if (currentVersion < 5) {
		migrateToV5(sql);
	}
	if (currentVersion < 6) {
		migrateToV6(sql);
	}

	if (versionRows.length === 0) {
		sql.exec('INSERT INTO schema_version (version) VALUES (?)', CURRENT_SCHEMA_VERSION);
	} else {
		sql.exec('UPDATE schema_version SET version = ?', CURRENT_SCHEMA_VERSION);
	}
}

/** Initial schema: tables, indexes, default preferences. */
function migrateToV1(sql: SqlStorage): void {
	sql.exec(`
		CREATE TABLE IF NOT EXISTS sessions (
			token TEXT PRIMARY KEY,
			created_at TEXT NOT NULL DEFAULT (datetime('now')),
			expires_at TEXT NOT NULL,
			last_used_at TEXT NOT NULL DEFAULT (datetime('now'))
		);

		CREATE TABLE IF NOT EXISTS profile (
			uuid TEXT PRIMARY KEY,
			username TEXT NOT NULL,
			skin_url TEXT,
			updated_at TEXT NOT NULL DEFAULT (datetime('now'))
		);

		CREATE TABLE IF NOT EXISTS friends (
			friend_uuid TEXT PRIMARY KEY,
			friend_username TEXT NOT NULL,
			friend_skin_url TEXT,
			status TEXT NOT NULL CHECK(status IN ('pending_outgoing', 'pending_incoming', 'accepted')),
			created_at TEXT NOT NULL DEFAULT (datetime('now'))
		);

		CREATE TABLE IF NOT EXISTS received_snaps (
			snap_id TEXT PRIMARY KEY,
			from_uuid TEXT NOT NULL,
			from_username TEXT NOT NULL,
			from_skin_url TEXT,
			media_type TEXT NOT NULL CHECK(media_type IN ('image', 'video')),
			r2_key TEXT NOT NULL,
			sent_at TEXT NOT NULL,
			received_at TEXT NOT NULL DEFAULT (datetime('now')),
			viewed_at TEXT
		);

		CREATE TABLE IF NOT EXISTS sent_snaps (
			snap_id TEXT PRIMARY KEY,
			upload_id TEXT NOT NULL,
			to_uuid TEXT NOT NULL,
			to_username TEXT NOT NULL,
			to_skin_url TEXT,
			media_type TEXT NOT NULL CHECK(media_type IN ('image', 'video')),
			r2_key TEXT NOT NULL,
			content_type TEXT NOT NULL DEFAULT '',
			media_size INTEGER NOT NULL DEFAULT 0,
			sent_at TEXT NOT NULL DEFAULT (datetime('now')),
			delivered_at TEXT,
			dropped_at TEXT,
			opened_at TEXT
		);

		CREATE TABLE IF NOT EXISTS preferences (
			key TEXT PRIMARY KEY,
			value TEXT NOT NULL
		);

		CREATE INDEX IF NOT EXISTS idx_received_snaps_unviewed
			ON received_snaps(viewed_at) WHERE viewed_at IS NULL;

		CREATE INDEX IF NOT EXISTS idx_sessions_expires
			ON sessions(expires_at);

		CREATE INDEX IF NOT EXISTS idx_sent_snaps_to
			ON sent_snaps(to_uuid);

		CREATE INDEX IF NOT EXISTS idx_sent_snaps_upload
			ON sent_snaps(upload_id);

		CREATE INDEX IF NOT EXISTS idx_friends_status
			ON friends(status);
	`);

	for (const [key, value] of Object.entries(DEFAULT_PREFERENCES)) {
		sql.exec('INSERT OR IGNORE INTO preferences (key, value) VALUES (?, ?)', key, String(value));
	}

	ensureColumn(sql, 'sent_snaps', 'content_type', "TEXT NOT NULL DEFAULT ''");
	ensureColumn(sql, 'sent_snaps', 'media_size', 'INTEGER NOT NULL DEFAULT 0');
}

/**
 * V2: normalize friend_uuid to dashless lowercase so WHERE clauses can use
 * the PRIMARY KEY index directly; drop the upload_rate_limit_events table
 * (replaced by in-memory tracking).
 */
function migrateToV2(sql: SqlStorage): void {
	sql.exec(`
		DELETE FROM friends WHERE rowid NOT IN (
			SELECT MAX(rowid) FROM friends
			GROUP BY replace(lower(friend_uuid), '-', '')
		);
		UPDATE friends
		SET friend_uuid = replace(lower(friend_uuid), '-', '')
		WHERE friend_uuid != replace(lower(friend_uuid), '-', '');
	`);

	sql.exec('DROP TABLE IF EXISTS upload_rate_limit_events');
}

/**
 * V3: support multi-recipient snap fanout with shared uploads plus recent-chat
 * metadata, while keeping legacy single-recipient rows addressable by snap_id.
 */
function migrateToV3(sql: SqlStorage): void {
	ensureColumn(sql, 'received_snaps', 'from_skin_url', 'TEXT');
	ensureColumn(sql, 'sent_snaps', 'upload_id', "TEXT NOT NULL DEFAULT ''");
	ensureColumn(sql, 'sent_snaps', 'to_skin_url', 'TEXT');
	ensureColumn(sql, 'sent_snaps', 'dropped_at', 'TEXT');

	sql.exec(`
		UPDATE sent_snaps
		SET upload_id = snap_id
		WHERE upload_id IS NULL OR upload_id = '';
	`);

	sql.exec('CREATE INDEX IF NOT EXISTS idx_sent_snaps_upload ON sent_snaps(upload_id)');
}

/**
 * V4: store shared snap metadata per upload and key received rows by upload_id
 * so inbox/viewer flows can expose caption + expiry data without duplicating it
 * per recipient row.
 */
function migrateToV4(sql: SqlStorage): void {
	ensureColumn(sql, 'received_snaps', 'upload_id', "TEXT NOT NULL DEFAULT ''");

	sql.exec(`
		UPDATE received_snaps
		SET upload_id = snap_id
		WHERE upload_id IS NULL OR upload_id = '';
	`);

	sql.exec(`
		CREATE TABLE IF NOT EXISTS snap_metadata (
			upload_id TEXT PRIMARY KEY,
			from_uuid TEXT NOT NULL DEFAULT '',
			media_type TEXT NOT NULL CHECK(media_type IN ('image', 'video')),
			content_type TEXT NOT NULL DEFAULT '',
			media_size INTEGER NOT NULL DEFAULT 0,
			caption_text TEXT NOT NULL DEFAULT '',
			caption_offset_y REAL NOT NULL DEFAULT 0,
			expiry_ms INTEGER NOT NULL DEFAULT ${DEFAULT_PREFERENCES.default_expiry_ms},
			sent_at TEXT NOT NULL DEFAULT (datetime('now')),
			expires_at TEXT NOT NULL
		);
	`);

	sql.exec(`
		INSERT OR IGNORE INTO snap_metadata (
			upload_id, from_uuid, media_type, content_type, media_size, caption_text, caption_offset_y, expiry_ms, sent_at, expires_at
		)
		SELECT
			ss.upload_id,
			COALESCE((SELECT uuid FROM profile LIMIT 1), ''),
			ss.media_type,
			ss.content_type,
			ss.media_size,
			'',
			0,
			${DEFAULT_PREFERENCES.default_expiry_ms},
			ss.sent_at,
			datetime(ss.sent_at, '+1 day')
		FROM sent_snaps ss
		WHERE ss.upload_id IS NOT NULL AND ss.upload_id != '';
	`);

	sql.exec('CREATE INDEX IF NOT EXISTS idx_received_snaps_upload ON received_snaps(upload_id)');
	sql.exec('CREATE INDEX IF NOT EXISTS idx_snap_metadata_expires_at ON snap_metadata(expires_at)');
}

/**
 * V5: authoritative pixel dimensions for snap media (client-provided), stored on
 * `snap_metadata` for inbox/viewer payloads. Uses `ALTER TABLE` so existing DO
 * databases created at v4 with these columns already present upgrade safely
 * (`ensureColumn` ignores duplicate column).
 */
function migrateToV5(sql: SqlStorage): void {
	ensureColumn(sql, 'snap_metadata', 'media_width', 'INTEGER NOT NULL DEFAULT 0');
	ensureColumn(sql, 'snap_metadata', 'media_height', 'INTEGER NOT NULL DEFAULT 0');
}

/**
 * V6: persist upload abuse controls and pending upload reservations so upload
 * limits survive Durable Object eviction and validation failures can accrue
 * strikes over time.
 */
function migrateToV6(sql: SqlStorage): void {
	sql.exec(`
		CREATE TABLE IF NOT EXISTS upload_attempts (
			upload_id TEXT PRIMARY KEY,
			r2_key TEXT NOT NULL DEFAULT '',
			hint_media_type TEXT NOT NULL CHECK(hint_media_type IN ('image', 'video')),
			hint_content_type TEXT NOT NULL DEFAULT '',
			reserved_size INTEGER NOT NULL DEFAULT 0,
			actual_media_type TEXT,
			actual_content_type TEXT,
			actual_size INTEGER,
			media_width INTEGER NOT NULL DEFAULT 0,
			media_height INTEGER NOT NULL DEFAULT 0,
			status TEXT NOT NULL CHECK(status IN ('pending', 'validated', 'invalid', 'abandoned')),
			last_error TEXT NOT NULL DEFAULT '',
			created_at TEXT NOT NULL DEFAULT (datetime('now')),
			updated_at TEXT NOT NULL DEFAULT (datetime('now')),
			expires_at TEXT NOT NULL
		);

		CREATE TABLE IF NOT EXISTS abuse_controls (
			id INTEGER PRIMARY KEY CHECK(id = 1),
			suspended_until TEXT,
			last_reason TEXT NOT NULL DEFAULT '',
			updated_at TEXT NOT NULL DEFAULT (datetime('now'))
		);
	`);

	sql.exec('INSERT OR IGNORE INTO abuse_controls (id, suspended_until, last_reason) VALUES (1, NULL, \'\')');
	sql.exec('CREATE INDEX IF NOT EXISTS idx_upload_attempts_created_at ON upload_attempts(created_at)');
	sql.exec('CREATE INDEX IF NOT EXISTS idx_upload_attempts_status_created_at ON upload_attempts(status, created_at)');
}

function ensureColumn(sql: SqlStorage, table: string, column: string, definition: string): void {
	try {
		sql.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
	} catch (err) {
		if (err instanceof Error && err.message.includes('duplicate column name')) {
			return;
		}
		throw err;
	}
}
