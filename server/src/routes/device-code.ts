import { json } from '../helpers';
import type { MSDeviceCodeResponse } from '../types';

export async function handleDeviceCode(env: Env): Promise<Response> {
	const res = await fetch('https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode', {
		method: 'POST',
		headers: { 'content-type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({
			client_id: env.MS_CLIENT_ID,
			scope: 'XboxLive.signin',
		}),
	});

	if (!res.ok) {
		const body = await res.text();
		return json({ error: 'microsoft_device_code_failed', details: body }, 502);
	}

	const data: MSDeviceCodeResponse = await res.json();
	return json({
		user_code: data.user_code,
		verification_uri: data.verification_uri,
		device_code: data.device_code,
		expires_in: data.expires_in,
		interval: data.interval,
		message: data.message,
	});
}
