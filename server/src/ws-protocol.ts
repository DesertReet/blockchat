// Max media size: 75MiB
export const MAX_MEDIA_SIZE = 78_643_200;
export const MAX_SNAP_RECIPIENTS = 10;

export const ALLOWED_CONTENT_TYPES = [
	'image/png',
	'image/jpeg',
	'image/gif',
	'video/mp4',
	'video/webm',
] as const;

export type AllowedContentType = (typeof ALLOWED_CONTENT_TYPES)[number];

export enum PreferenceToggle {
	Off = 0,
	On = 1,
}

export const DEFAULT_PREFERENCES = {
	default_expiry_ms: 86_400_000,
	notifications: PreferenceToggle.On,
	friend_requests: PreferenceToggle.On,
} as const;

/**
 * Normalizes any BlockChat identity UUID to the canonical dashless lowercase form used for DO names,
 * database rows, and websocket payloads so all paths key the same player.
 */
export function normalizeUuid(rawUuid: string): string {
	return rawUuid.trim().replace(/-/g, '').toLowerCase();
}

// ── Client → Server messages ──

export interface SendSnapMessage {
	type: 'send_snap';
	to: string[];
	media_type: 'image' | 'video';
	media_size: number;
	content_type: AllowedContentType;
	media_width?: number;
	media_height?: number;
	caption_text?: string;
	caption_offset_y?: number;
	expiry_ms?: number;
}

export interface SnapUploadedMessage {
	type: 'snap_uploaded';
	snap_id: string;
}

export interface ViewSnapMessage {
	type: 'view_snap';
	snap_id: string;
}

export interface SnapViewedMessage {
	type: 'snap_viewed';
	snap_id: string;
}

export interface AddFriendMessage {
	type: 'add_friend';
	uuid: string;
}

export interface AcceptFriendMessage {
	type: 'accept_friend';
	uuid: string;
}

export interface RemoveFriendMessage {
	type: 'remove_friend';
	uuid: string;
}

export interface GetFriendsMessage {
	type: 'get_friends';
}

export interface GetSnapsMessage {
	type: 'get_snaps';
}

export interface GetChatRecentsMessage {
	type: 'get_chat_recents';
}

export interface PingMessage {
	type: 'ping';
}

export interface SessionRefreshMessage {
	type: 'session_refresh';
}

export interface SessionRevokeMessage {
	type: 'session_revoke';
}

export interface SetPreferenceMessage {
	type: 'set_preference';
	key: keyof typeof DEFAULT_PREFERENCES;
	value: number;
}

export interface GetPreferencesMessage {
	type: 'get_preferences';
}

export interface SearchUserMessage {
	type: 'search_user';
	username: string;
}

export interface DeleteAccountMessage {
	type: 'delete_account';
}

export type ClientMessage =
	| SendSnapMessage
	| SnapUploadedMessage
	| ViewSnapMessage
	| SnapViewedMessage
	| AddFriendMessage
	| AcceptFriendMessage
	| RemoveFriendMessage
	| GetFriendsMessage
	| GetSnapsMessage
	| GetChatRecentsMessage
	| PingMessage
	| SessionRefreshMessage
	| SessionRevokeMessage
	| SetPreferenceMessage
	| GetPreferencesMessage
	| SearchUserMessage
	| DeleteAccountMessage;

// ── Server → Client messages ──

export interface SnapUploadUrlMessage {
	type: 'snap_upload_url';
	snap_id: string;
	upload_url: string;
	expires_in: number;
}

export interface SnapDownloadUrlMessage {
	type: 'snap_download_url';
	snap_id: string;
	upload_id: string;
	download_url: string;
	expires_in: number;
	media_type: 'image' | 'video';
	content_type: string;
	media_width: number;
	media_height: number;
	caption_text: string;
	caption_offset_y: number;
	expiry_ms: number;
	sent_at: string;
}

export interface SnapReceivedMessage {
	type: 'snap_received';
	snap_id: string;
	upload_id: string;
	from: string;
	from_username: string;
	from_skin_url: string | null;
	media_type: 'image' | 'video';
	content_type: string;
	media_width: number;
	media_height: number;
	caption_text: string;
	caption_offset_y: number;
	expiry_ms: number;
	sent_at: string;
}

export interface SnapDeliveredMessage {
	type: 'snap_delivered';
	snap_id: string;
}

export interface SnapOpenedMessage {
	type: 'snap_opened';
	snap_id: string;
	by: string;
}

export interface FriendListMessage {
	type: 'friend_list';
	friends: Array<{
		uuid: string;
		username: string;
		skin_url: string | null;
		status: 'pending_outgoing' | 'pending_incoming' | 'accepted';
	}>;
}

export interface FriendRequestMessage {
	type: 'friend_request';
	from: string;
	from_username: string;
	from_skin_url: string | null;
}

export interface FriendRequestSentMessage {
	type: 'friend_request_sent';
	uuid: string;
	username: string;
}

export interface FriendAddedMessage {
	type: 'friend_added';
	uuid: string;
	username: string;
}

export interface FriendRemovedMessage {
	type: 'friend_removed';
	uuid: string;
}

export interface SnapListMessage {
	type: 'snap_list';
	snaps: Array<{
		snap_id: string;
		upload_id: string;
		from: string;
		from_username: string;
		from_skin_url: string | null;
		media_type: 'image' | 'video';
		content_type: string;
		media_width: number;
		media_height: number;
		caption_text: string;
		caption_offset_y: number;
		expiry_ms: number;
		sent_at: string;
		sender_unread_position: number;
		sender_unread_total: number;
		sender_oldest_unread_snap_id: string;
		sender_next_unread_snap_id: string | null;
	}>;
}

export interface ChatRecentsMessage {
	type: 'chat_recents';
	recents: Array<{
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
	}>;
}

export interface SessionTokenMessage {
	type: 'session_token';
	token: string;
	expires_at: string;
}

export interface ErrorMessage {
	type: 'error';
	code: string;
	message: string;
	ref_id?: string;
}

export interface PongMessage {
	type: 'pong';
}

export interface PreferencesMessage {
	type: 'preferences';
	preferences: Record<string, number>;
}

export interface PreferenceSetMessage {
	type: 'preference_set';
	key: string;
	value: number;
}

export interface UserSearchResultMessage {
	type: 'user_search_result';
	query: string;
	user: {
		uuid: string;
		username: string;
		skin_url: string | null;
	} | null;
}

export interface AccountDeletedMessage {
	type: 'account_deleted';
}

export type ServerMessage =
	| SnapUploadUrlMessage
	| SnapDownloadUrlMessage
	| SnapReceivedMessage
	| SnapDeliveredMessage
	| SnapOpenedMessage
	| FriendListMessage
	| FriendRequestMessage
	| FriendAddedMessage
	| FriendRemovedMessage
	| SnapListMessage
	| ChatRecentsMessage
	| SessionTokenMessage
	| ErrorMessage
	| PongMessage
	| PreferencesMessage
	| PreferenceSetMessage
	| UserSearchResultMessage
	| FriendRequestSentMessage
	| AccountDeletedMessage;
