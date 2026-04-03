import { normalizeUuid } from './ws-protocol';

export class AuthError extends Error {
	constructor(
		public status: number,
		public step: string,
		public override message: string,
		public details?: unknown
	) {
		super(message);
	}
}

export function json(data: unknown, status = 200): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { 'content-type': 'application/json;charset=UTF-8' },
	});
}

type MojangSessionProfile = {
	id?: string;
	name?: string;
	properties?: Array<{ name: string; value: string }>;
};

export type MojangPublicProfile = {
	uuid: string;
	username: string;
	skinUrl: string | null;
};

/** 3-day cache for successful Mojang responses, 1-hour cache for 404s. */
const MOJANG_CACHE_CF: RequestInitCfProperties = {
	cacheTtlByStatus: { '200-299': 259_200, '404': 3_600 },
};

/**
 * Resolves a Java edition username to UUID and skin URL using Mojang public APIs.
 * Results are CDN-cached for 3 days.
 */
export async function fetchMojangPublicProfile(rawUsername: string): Promise<MojangPublicProfile> {
	const trimmed = rawUsername.trim();
	if (!trimmed) {
		throw new AuthError(400, 'invalid_username', 'username must be a non-empty string');
	}

	const encoded = encodeURIComponent(trimmed);
	const profileRes = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encoded}`, {
		cf: MOJANG_CACHE_CF,
	});
	if (profileRes.status === 404) {
		throw new AuthError(404, 'unknown_username', 'No Minecraft profile found for that username');
	}
	if (!profileRes.ok) {
		const text = await profileRes.text();
		throw new AuthError(502, 'mojang_profile', `Mojang profile lookup failed (${profileRes.status})`, text);
	}

	const basic = (await profileRes.json()) as { id: string; name: string };
	const uuid = basic.id;
	const canonicalName = basic.name;

	const skinUrl = await fetchMojangSkinUrlByUuid(uuid);

	return { uuid: normalizeUuid(uuid), username: canonicalName, skinUrl };
}

/**
 * Resolves a Java edition UUID to the canonical Mojang username and skin URL.
 * Results are CDN-cached for 3 days.
 */
export async function fetchMojangPublicProfileByUuid(rawUuid: string): Promise<MojangPublicProfile> {
	const uuid = normalizeUuid(rawUuid);
	if (!/^[0-9a-f]{32}$/.test(uuid)) {
		throw new AuthError(400, 'invalid_uuid', 'uuid must be a 32-character lowercase hexadecimal string');
	}

	const profileRes = await fetch(`https://sessionserver.mojang.com/session/minecraft/profile/${uuid}`, {
		cf: MOJANG_CACHE_CF,
	});
	if (profileRes.status === 204 || profileRes.status === 404) {
		throw new AuthError(404, 'unknown_uuid', 'No Minecraft profile found for that UUID');
	}
	if (!profileRes.ok) {
		const text = await profileRes.text();
		throw new AuthError(502, 'mojang_session', `Mojang session profile failed (${profileRes.status})`, text);
	}

	const session = (await profileRes.json()) as MojangSessionProfile;
	if (!session.name) {
		throw new AuthError(502, 'mojang_session', 'Mojang session profile response missing a username');
	}
	return {
		uuid,
		username: session.name,
		skinUrl: readMojangSkinUrl(session),
	};
}

async function fetchMojangSkinUrlByUuid(uuid: string): Promise<string | null> {
	const sessionRes = await fetch(`https://sessionserver.mojang.com/session/minecraft/profile/${uuid}`, {
		cf: MOJANG_CACHE_CF,
	});
	if (!sessionRes.ok) {
		const text = await sessionRes.text();
		throw new AuthError(502, 'mojang_session', `Mojang session profile failed (${sessionRes.status})`, text);
	}

	return readMojangSkinUrl((await sessionRes.json()) as MojangSessionProfile);
}

function readMojangSkinUrl(session: MojangSessionProfile): string | null {
	const texturesProp = session.properties?.find((p) => p.name === 'textures');
	if (!texturesProp?.value) {
		return null;
	}

	try {
		const decoded = JSON.parse(atob(texturesProp.value)) as {
			textures?: { SKIN?: { url?: string } };
		};
		return decoded.textures?.SKIN?.url ?? null;
	} catch {
		return null;
	}
}
