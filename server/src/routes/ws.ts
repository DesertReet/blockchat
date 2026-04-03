import { errorSummary, testLog } from '../test-log';
import { normalizeUuid } from '../ws-protocol';

export async function handleWsUpgrade(request: Request, env: Env): Promise<Response> {
	const upgradeHeader = request.headers.get('Upgrade');
	const url = new URL(request.url);
	const uuid = url.searchParams.get('uuid');
	const token = url.searchParams.get('token');
	testLog(env, 'route.ws', 'upgrade_attempt', {
		hasUpgradeHeader: upgradeHeader !== null,
		hasUuid: Boolean(uuid),
		hasToken: Boolean(token),
	});

	if (upgradeHeader !== 'websocket') {
		testLog(env, 'route.ws', 'upgrade_missing_header', { status: 426 });
		return new Response('Expected WebSocket upgrade', { status: 426 });
	}

	if (!uuid || !token) {
		testLog(env, 'route.ws', 'missing_query_params', { status: 400, hasUuid: Boolean(uuid), hasToken: Boolean(token) });
		return new Response('Missing uuid or token query parameter', { status: 400 });
	}

	// Validate UUID format (32 hex chars, Minecraft UUID without dashes)
	if (!/^[0-9a-f]{32}$/i.test(uuid)) {
		testLog(env, 'route.ws', 'invalid_uuid_format', { status: 400, uuid });
		return new Response('Invalid UUID format', { status: 400 });
	}

	const canonicalUuid = normalizeUuid(uuid);
	const id = env.USER_DO.idFromName(canonicalUuid);
	const stub = env.USER_DO.get(id);
	testLog(env, 'route.ws', 'handoff_to_do', { uuid: canonicalUuid });

	try {
		const response = await stub.fetch(request);
		testLog(env, 'route.ws', 'handoff_response', { uuid: canonicalUuid, status: response.status });
		return response;
	} catch (err) {
		testLog(env, 'route.ws', 'handoff_error', { uuid: canonicalUuid, ...errorSummary(err) });
		throw err;
	}
}
