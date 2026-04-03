/** Public GitHub repo that publishes BlockChat release jars. */
const GITHUB_REPO = 'DesertReet/blockchat';

const GITHUB_LATEST_RELEASE_URL = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;

const FALLBACK_MOD_VERSION = '1.0.0';
const FALLBACK_RELEASE_TAG = '1.0.0';

/** Used when the GitHub API is unreachable so the homepage still offers downloads. */
export const FALLBACK_DOWNLOAD_LINKS: HomepageDownloadLinks = {
	releaseTag: FALLBACK_RELEASE_TAG,
	modVersion: FALLBACK_MOD_VERSION,
	windowsUrl: `https://github.com/${GITHUB_REPO}/releases/download/${FALLBACK_RELEASE_TAG}/desertreet-blockchat-${FALLBACK_MOD_VERSION}-windows.jar`,
	macosArm64Url: `https://github.com/${GITHUB_REPO}/releases/download/${FALLBACK_RELEASE_TAG}/desertreet-blockchat-${FALLBACK_MOD_VERSION}-macos-arm64.jar`,
	macosAmd64Url: `https://github.com/${GITHUB_REPO}/releases/download/${FALLBACK_RELEASE_TAG}/desertreet-blockchat-${FALLBACK_MOD_VERSION}-macos-amd64.jar`,
};

const RELEASE_LINKS_CACHE_TTL_MS = 10 * 60 * 1000;

type GitHubReleaseAsset = {
	name: string;
	browser_download_url: string;
};

type GitHubLatestReleaseResponse = {
	tag_name: string;
	assets: GitHubReleaseAsset[];
};

export type HomepageDownloadLinks = {
	/** Git tag for this release (as published on GitHub). */
	releaseTag: string;
	/** Mod version embedded in jar filenames (leading "v" stripped from the tag when present). */
	modVersion: string;
	windowsUrl: string;
	macosArm64Url: string;
	macosAmd64Url: string;
};

let releaseLinksCache: { links: HomepageDownloadLinks; expiresAt: number } | null = null;

/**
 * Clears the in-memory cache of latest-release download URLs (used by tests for isolation).
 */
export function clearHomepageReleaseLinksCache(): void {
	releaseLinksCache = null;
}

/**
 * Escapes text for safe insertion into HTML text nodes and attribute values.
 */
function escapeHtml(text: string): string {
	return text
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&#39;');
}

function modVersionFromTag(tag: string): string {
	return tag.replace(/^v/i, '');
}

function assetUrlForSuffix(assets: GitHubReleaseAsset[], suffix: string): string | null {
	const found = assets.find((a) => a.name.endsWith(suffix));
	return found?.browser_download_url ?? null;
}

function releaseArtifactUrl(releaseTag: string, modVersion: string, platformSuffix: string): string {
	const filename = `desertreet-blockchat-${modVersion}${platformSuffix}`;
	const encTag = encodeURIComponent(releaseTag);
	const encFile = encodeURIComponent(filename);
	return `https://github.com/${GITHUB_REPO}/releases/download/${encTag}/${encFile}`;
}

/**
 * Fetches the latest GitHub release and resolves direct download URLs for the three platform jars.
 * Falls back to a pinned release if the API fails or assets are missing.
 */
export async function fetchHomepageDownloadLinks(env: Env): Promise<HomepageDownloadLinks> {
	try {
		const headers: HeadersInit = {
			Accept: 'application/vnd.github+json',
			'X-GitHub-Api-Version': '2022-11-28',
			'User-Agent': 'BlockChat-Server (https://blockchat.desertreet.com)',
		};
		const token =
			typeof env.GITHUB_TOKEN === 'string' && env.GITHUB_TOKEN.length > 0 ? env.GITHUB_TOKEN : null;
		if (token) {
			(headers as Record<string, string>).Authorization = `Bearer ${token}`;
		}

		let response: Response;
		try {
			response = await fetch(GITHUB_LATEST_RELEASE_URL, { headers });
		} catch {
			return FALLBACK_DOWNLOAD_LINKS;
		}

		if (!response.ok) {
			return FALLBACK_DOWNLOAD_LINKS;
		}

		let body: GitHubLatestReleaseResponse;
		try {
			body = (await response.json()) as GitHubLatestReleaseResponse;
		} catch {
			return FALLBACK_DOWNLOAD_LINKS;
		}

		const releaseTag = typeof body.tag_name === 'string' ? body.tag_name : FALLBACK_RELEASE_TAG;
		const modVersion = modVersionFromTag(releaseTag);
		const assets = Array.isArray(body.assets) ? body.assets : [];

		const windowsUrl =
			assetUrlForSuffix(assets, '-windows.jar') ?? releaseArtifactUrl(releaseTag, modVersion, '-windows.jar');
		const macosArm64Url =
			assetUrlForSuffix(assets, '-macos-arm64.jar') ??
			releaseArtifactUrl(releaseTag, modVersion, '-macos-arm64.jar');
		const macosAmd64Url =
			assetUrlForSuffix(assets, '-macos-amd64.jar') ??
			releaseArtifactUrl(releaseTag, modVersion, '-macos-amd64.jar');

		return {
			releaseTag,
			modVersion,
			windowsUrl,
			macosArm64Url,
			macosAmd64Url,
		};
	} catch {
		return FALLBACK_DOWNLOAD_LINKS;
	}
}

/**
 * Returns latest-release download links, using a short-lived in-worker cache to limit GitHub API traffic.
 */
export async function getHomepageDownloadLinks(env: Env): Promise<HomepageDownloadLinks> {
	const now = Date.now();
	if (releaseLinksCache && releaseLinksCache.expiresAt > now) {
		return releaseLinksCache.links;
	}
	const links = await fetchHomepageDownloadLinks(env);
	releaseLinksCache = { links, expiresAt: now + RELEASE_LINKS_CACHE_TTL_MS };
	return links;
}

export { escapeHtml };
