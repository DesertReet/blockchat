import { AwsClient } from 'aws4fetch';

const DEFAULT_PRESIGN_EXPIRY_SECONDS = 300; // 5 minutes
const MAX_PRESIGN_EXPIRY_SECONDS = 604_800; // 7 days (SigV4 query auth max)
const MIN_PRESIGN_EXPIRY_SECONDS = 1;

export function createR2Client(env: Env): AwsClient {
	return new AwsClient({
		service: 's3',
		region: 'auto',
		accessKeyId: env.R2_ACCESS_KEY_ID,
		secretAccessKey: env.R2_SECRET_ACCESS_KEY,
	});
}

function r2Endpoint(env: Env, key: string): string {
	return `https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${env.R2_BUCKET_NAME}/${key}`;
}

export function clampPresignExpirySeconds(rawSeconds: number): number {
	if (!Number.isFinite(rawSeconds)) {
		return DEFAULT_PRESIGN_EXPIRY_SECONDS;
	}
	const clamped = Math.floor(rawSeconds);
	if (clamped < MIN_PRESIGN_EXPIRY_SECONDS) {
		return MIN_PRESIGN_EXPIRY_SECONDS;
	}
	if (clamped > MAX_PRESIGN_EXPIRY_SECONDS) {
		return MAX_PRESIGN_EXPIRY_SECONDS;
	}
	return clamped;
}

export function expiryMsToPresignSeconds(expiryMs: number): number {
	if (!Number.isFinite(expiryMs)) {
		return DEFAULT_PRESIGN_EXPIRY_SECONDS;
	}
	return clampPresignExpirySeconds(Math.ceil(expiryMs / 1000));
}

export async function generateUploadUrl(
	client: AwsClient,
	env: Env,
	key: string,
	contentType: string,
	contentLength: number,
	expiresInSeconds = DEFAULT_PRESIGN_EXPIRY_SECONDS
): Promise<string> {
	const ttlSeconds = clampPresignExpirySeconds(expiresInSeconds);
	const url = r2Endpoint(env, key);
	const signed = await client.sign(
		new Request(`${url}?X-Amz-Expires=${ttlSeconds}`, {
			method: 'PUT',
			headers: {
				'content-type': contentType,
				'content-length': String(contentLength),
			},
		}),
		{ aws: { signQuery: true, allHeaders: true } }
	);
	return signed.url.toString();
}

export async function generateDownloadUrl(
	client: AwsClient,
	env: Env,
	key: string,
	expiresInSeconds = DEFAULT_PRESIGN_EXPIRY_SECONDS
): Promise<string> {
	const ttlSeconds = clampPresignExpirySeconds(expiresInSeconds);
	const url = r2Endpoint(env, key);
	const signed = await client.sign(new Request(`${url}?X-Amz-Expires=${ttlSeconds}`, { method: 'GET' }), {
		aws: { signQuery: true },
	});
	return signed.url.toString();
}

export { DEFAULT_PRESIGN_EXPIRY_SECONDS as PRESIGN_EXPIRY, MAX_PRESIGN_EXPIRY_SECONDS };
