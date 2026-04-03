import { AuthError, fetchMojangPublicProfile, json } from '../helpers';
import { errorSummary, isTestMode, testErrorLog, testLog } from '../test-log';
import { normalizeUuid } from '../ws-protocol';
import type { MSTokenResponse, MSTokenError, XboxLiveAuthResponse, MinecraftAuthResponse, MinecraftProfile } from '../types';

/**
 * Compares Minecraft usernames case-insensitively (ASCII; matches typical Mojang name rules).
 */
function minecraftNamesMatchCaseInsensitive(authenticated: string, claimed: string): boolean {
	return authenticated.trim().toLowerCase() === claimed.trim().toLowerCase();
}

/**
 * Creates a session in the player DO and returns the standard verify JSON body.
 */
async function issueVerifySession(
	env: Env,
	uuid: string,
	username: string,
	skinUrl: string | null
): Promise<Response> {
	const canonicalUuid = normalizeUuid(uuid);
	const bytes = new Uint8Array(32);
	crypto.getRandomValues(bytes);
	const sessionToken = Array.from(bytes)
		.map((b) => b.toString(16).padStart(2, '0'))
		.join('');

	const doId = env.USER_DO.idFromName(canonicalUuid);
	const doObjectId = String(doId);
	const stub = env.USER_DO.get(doId);
	testLog(env, 'route.verify', 'session_issue_start', {
		uuid: canonicalUuid,
		doName: canonicalUuid,
		doObjectId,
		method: 'createSession',
		username,
		hasSkin: skinUrl !== null,
	});
	try {
		await stub.createSession(sessionToken, username, skinUrl);
		testLog(env, 'route.verify', 'session_issued', {
			uuid: canonicalUuid,
			doName: canonicalUuid,
			doObjectId,
			method: 'createSession',
			username,
			hasSkin: skinUrl !== null,
		});
	} catch (err) {
		testErrorLog(env, 'route.verify', 'session_issue_error', {
			uuid: canonicalUuid,
			doName: canonicalUuid,
			doObjectId,
			method: 'createSession',
			username,
			hasSkin: skinUrl !== null,
			...errorSummary(err),
		});
		throw err;
	}

	return json({
		uuid: canonicalUuid,
		username,
		skin_url: skinUrl,
		session_token: sessionToken,
	});
}

export async function handleVerify(request: Request, env: Env): Promise<Response> {
	let body: { device_code?: string; username?: string };
	try {
		body = await request.json();
	} catch {
		testLog(env, 'route.verify', 'invalid_json');
		return json({ error: 'invalid_json', message: 'Request body must be valid JSON' }, 400);
	}

	const testMode = isTestMode(env);
	testLog(env, 'route.verify', 'request', {
		testMode,
		hasUsername: typeof body.username === 'string',
		hasDeviceCode: typeof body.device_code === 'string',
	});

	if (testMode) {
		if (body.username === undefined || typeof body.username !== 'string') {
			testLog(env, 'route.verify', 'missing_username');
			return json({ error: 'missing_username', message: 'username is required in test mode' }, 400);
		}
		try {
			const { uuid, username, skinUrl } = await fetchMojangPublicProfile(body.username);
			testLog(env, 'route.verify', 'test_mode_profile_resolved', {
				uuid,
				username,
				hasSkin: skinUrl !== null,
			});
			return await issueVerifySession(env, uuid, username, skinUrl);
		} catch (err) {
			if (err instanceof AuthError) {
				testLog(env, 'route.verify', 'test_mode_auth_error', {
					step: err.step,
					status: err.status,
				});
				return json({ error: err.step, message: err.message, details: err.details }, err.status);
			}
			testLog(env, 'route.verify', 'test_mode_unhandled_error', errorSummary(err));
			throw err;
		}
	}

	if (!body.device_code || typeof body.device_code !== 'string') {
		testLog(env, 'route.verify', 'missing_device_code');
		return json({ error: 'missing_device_code', message: 'device_code is required' }, 400);
	}

	if (body.username !== undefined && typeof body.username !== 'string') {
		testLog(env, 'route.verify', 'invalid_username');
		return json({ error: 'invalid_username', message: 'username must be a string when provided' }, 400);
	}

	try {
		testLog(env, 'route.verify', 'auth_exchange_start');
		const msToken = await exchangeDeviceCode(body.device_code, env);
		const { token: xblToken, userHash } = await xboxLiveAuth(msToken.access_token);
		const xstsToken = await xstsAuth(xblToken);
		const mcAuth = await minecraftLogin(userHash, xstsToken);
		const profile = await minecraftProfile(mcAuth.access_token);
		testLog(env, 'route.verify', 'auth_profile_resolved', {
			uuid: profile.id,
			username: profile.name,
		});

		const claimed = typeof body.username === 'string' ? body.username.trim() : '';
		if (claimed.length > 0 && !minecraftNamesMatchCaseInsensitive(profile.name, claimed)) {
			testLog(env, 'route.verify', 'username_mismatch', {
				expected: profile.name,
				provided: body.username,
			});
			return json(
				{
					error: 'username_mismatch',
					message: 'Provided username does not match the authenticated Minecraft account',
					details: { expected: profile.name, provided: body.username },
				},
				403
			);
		}

		const skinUrl = profile.skins?.find((s) => s.state === 'ACTIVE')?.url ?? profile.skins?.[0]?.url ?? null;
		testLog(env, 'route.verify', 'auth_success', {
			uuid: profile.id,
			username: profile.name,
			hasSkin: skinUrl !== null,
		});

		return await issueVerifySession(env, profile.id, profile.name, skinUrl);
	} catch (err) {
		if (err instanceof AuthError) {
			testLog(env, 'route.verify', 'auth_error', {
				step: err.step,
				status: err.status,
			});
			return json({ error: err.step, message: err.message, details: err.details }, err.status);
		}
		testLog(env, 'route.verify', 'unhandled_error', errorSummary(err));
		throw err;
	}
}

async function exchangeDeviceCode(deviceCode: string, env: Env): Promise<MSTokenResponse> {
	const res = await fetch('https://login.microsoftonline.com/consumers/oauth2/v2.0/token', {
		method: 'POST',
		headers: { 'content-type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({
			grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
			client_id: env.MS_CLIENT_ID,
			device_code: deviceCode,
		}),
	});

	if (!res.ok) {
		const err: MSTokenError = await res.json();
		throw new AuthError(
			err.error === 'authorization_pending' ? 400 : err.error === 'expired_token' ? 410 : 400,
			err.error,
			err.error_description
		);
	}

	return res.json();
}

async function xboxLiveAuth(msAccessToken: string): Promise<{ token: string; userHash: string }> {
	const res = await fetch('https://user.auth.xboxlive.com/user/authenticate', {
		method: 'POST',
		headers: { 'content-type': 'application/json', accept: 'application/json' },
		body: JSON.stringify({
			Properties: {
				AuthMethod: 'RPS',
				SiteName: 'user.auth.xboxlive.com',
				RpsTicket: `d=${msAccessToken}`,
			},
			RelyingParty: 'http://auth.xboxlive.com',
			TokenType: 'JWT',
		}),
	});

	if (!res.ok) {
		const body = await res.text();
		throw new AuthError(502, 'xbox_live_auth', `Xbox Live authentication failed (${res.status})`, body);
	}

	const data: XboxLiveAuthResponse = await res.json();
	return { token: data.Token, userHash: data.DisplayClaims.xui[0].uhs };
}

async function xstsAuth(xblToken: string): Promise<string> {
	const res = await fetch('https://xsts.auth.xboxlive.com/xsts/authorize', {
		method: 'POST',
		headers: { 'content-type': 'application/json', accept: 'application/json' },
		body: JSON.stringify({
			Properties: {
				SandboxId: 'RETAIL',
				UserTokens: [xblToken],
			},
			RelyingParty: 'rp://api.minecraftservices.com/',
			TokenType: 'JWT',
		}),
	});

	if (!res.ok) {
		const body = await res.json().catch(() => ({}));
		const xerr = (body as Record<string, unknown>).XErr;

		const xstsErrors: Record<number, string> = {
			2148916233: 'This Microsoft account has no Xbox account. Sign up at xbox.com first.',
			2148916235: 'Xbox Live is not available in your country.',
			2148916236: 'Adult verification needed.',
			2148916237: 'Adult verification needed.',
			2148916238: 'This is a child account. An adult must add it to a Microsoft family.',
		};

		const message =
			typeof xerr === 'number' ? (xstsErrors[xerr] ?? `XSTS error ${xerr}`) : `XSTS authorization failed (${res.status})`;
		throw new AuthError(403, 'xsts_auth', message, body);
	}

	const data: XboxLiveAuthResponse = await res.json();
	return data.Token;
}

async function minecraftLogin(userHash: string, xstsToken: string): Promise<MinecraftAuthResponse> {
	const res = await fetch('https://api.minecraftservices.com/authentication/login_with_xbox', {
		method: 'POST',
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			identityToken: `XBL3.0 x=${userHash};${xstsToken}`,
		}),
	});

	if (!res.ok) {
		const body = await res.text();
		throw new AuthError(502, 'minecraft_login', `Minecraft login failed (${res.status})`, body);
	}

	return res.json();
}

async function minecraftProfile(mcAccessToken: string): Promise<MinecraftProfile> {
	const res = await fetch('https://api.minecraftservices.com/minecraft/profile', {
		headers: { authorization: `Bearer ${mcAccessToken}` },
	});

	if (!res.ok) {
		if (res.status === 404) {
			throw new AuthError(404, 'no_minecraft_profile', 'This account does not own Minecraft');
		}
		const body = await res.text();
		throw new AuthError(502, 'minecraft_profile', `Failed to fetch Minecraft profile (${res.status})`, body);
	}

	return res.json();
}
