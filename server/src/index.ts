import { handleHomepage } from './routes/homepage';
import { handleLicense, handlePrivacyPolicy, handleTermsOfService } from './routes/legal-pages';
import { handleDeviceCode } from './routes/device-code';
import { handleVerify } from './routes/verify';
import { handleWsUpgrade } from './routes/ws';
import { testLog } from './test-log';

export { BlockChatUserDurableObject } from './do/user-do';

export default {
	async fetch(request, env, ctx): Promise<Response> {
		const { pathname } = new URL(request.url);
		const method = request.method;
		const traceRoute = pathname.startsWith('/api/auth/') || pathname === '/ws';
		if (traceRoute) {
			testLog(env, 'edge.fetch', 'dispatch', { method, pathname });
		}

		if (pathname === '/') {
			if (method !== 'GET' && method !== 'HEAD') {
				testLog(env, 'edge.fetch', 'method_not_allowed', { method, pathname, status: 405 });
				return new Response('Method Not Allowed', {
					status: 405,
					headers: { allow: 'GET, HEAD', 'content-type': 'text/plain;charset=UTF-8' },
				});
			}
			return handleHomepage();
		}

		if (
			pathname === '/privacy-policy' ||
			pathname === '/terms-of-service' ||
			pathname === '/license'
		) {
			if (method !== 'GET' && method !== 'HEAD') {
				testLog(env, 'edge.fetch', 'method_not_allowed', { method, pathname, status: 405 });
				return new Response('Method Not Allowed', {
					status: 405,
					headers: { allow: 'GET, HEAD', 'content-type': 'text/plain;charset=UTF-8' },
				});
			}
			if (pathname === '/privacy-policy') return handlePrivacyPolicy();
			if (pathname === '/terms-of-service') return handleTermsOfService();
			return handleLicense();
		}

		if (pathname === '/api/auth/device-code') {
			if (method !== 'POST') {
				testLog(env, 'edge.fetch', 'method_not_allowed', { method, pathname, status: 405 });
				return new Response('Method Not Allowed', {
					status: 405,
					headers: { allow: 'POST', 'content-type': 'text/plain;charset=UTF-8' },
				});
			}
			const response = await handleDeviceCode(env);
			if (response.status >= 400) {
				testLog(env, 'edge.fetch', 'route_error', { method, pathname, status: response.status });
			}
			return response;
		}

		if (pathname === '/api/auth/verify') {
			if (method !== 'POST') {
				testLog(env, 'edge.fetch', 'method_not_allowed', { method, pathname, status: 405 });
				return new Response('Method Not Allowed', {
					status: 405,
					headers: { allow: 'POST', 'content-type': 'text/plain;charset=UTF-8' },
				});
			}
			const response = await handleVerify(request, env);
			if (response.status >= 400) {
				testLog(env, 'edge.fetch', 'route_error', { method, pathname, status: response.status });
			}
			return response;
		}

		if (pathname === '/ws') {
			if (method !== 'GET') {
				testLog(env, 'edge.fetch', 'method_not_allowed', { method, pathname, status: 405 });
				return new Response('Method Not Allowed', {
					status: 405,
					headers: { allow: 'GET', 'content-type': 'text/plain;charset=UTF-8' },
				});
			}
			const response = await handleWsUpgrade(request, env);
			if (response.status >= 400) {
				testLog(env, 'edge.fetch', 'route_error', { method, pathname, status: response.status });
			}
			return response;
		}

		return new Response('Not Found', {
			status: 404,
			headers: { 'content-type': 'text/plain;charset=UTF-8' },
		});
	},
} satisfies ExportedHandler<Env>;
