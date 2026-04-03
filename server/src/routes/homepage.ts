import {
	escapeHtml,
	FALLBACK_DOWNLOAD_LINKS,
	type HomepageDownloadLinks,
	getHomepageDownloadLinks,
} from '../github-latest-release';

/** Public site origin for canonical and social preview URLs (matches wrangler custom domain). */
const HOMEPAGE_ORIGIN = 'https://blockchat.desertreet.com';

const HOMEPAGE_DESCRIPTION =
	'A social mod for Minecraft where you can instantly send screenshots and videos to friends.';

/** Featured homepage trailer / overview (youtube.com/watch?v=…). */
const HOMEPAGE_YOUTUBE_VIDEO_ID = 'PGMen8-84xw';

/**
 * Builds the public BlockChat homepage HTML, including GitHub release download links for the latest jars.
 */
function buildHomepageHtml(links: HomepageDownloadLinks): string {
	const modVersionHtml = escapeHtml(links.modVersion);
	const winUrl = escapeHtml(links.windowsUrl);
	const armUrl = escapeHtml(links.macosArm64Url);
	const amdUrl = escapeHtml(links.macosAmd64Url);

	return `<!doctype html>
<html lang="en">
	<head>
		<meta charset="utf-8" />
		<meta name="viewport" content="width=device-width, initial-scale=1" />
		<title>BlockChat</title>
		<meta name="description" content="${HOMEPAGE_DESCRIPTION}" />
		<link rel="canonical" href="${HOMEPAGE_ORIGIN}/" />
		<meta name="theme-color" content="#f5efe2" />

		<meta property="og:site_name" content="BlockChat" />
		<meta property="og:title" content="BlockChat" />
		<meta property="og:description" content="${HOMEPAGE_DESCRIPTION}" />
		<meta property="og:url" content="${HOMEPAGE_ORIGIN}/" />
		<meta property="og:type" content="website" />
		<meta property="og:image" content="${HOMEPAGE_ORIGIN}/favicon.png" />
		<meta property="og:image:alt" content="BlockChat" />
		<meta property="og:locale" content="en_US" />

		<meta name="twitter:card" content="summary" />
		<meta name="twitter:title" content="BlockChat" />
		<meta name="twitter:description" content="${HOMEPAGE_DESCRIPTION}" />
		<meta name="twitter:image" content="${HOMEPAGE_ORIGIN}/favicon.png" />

		<link rel="icon" href="/favicon.png" type="image/png" sizes="any" />
		<link rel="apple-touch-icon" href="/favicon.png" />
		<style>
			:root {
				color-scheme: light;
				font-family: "Trebuchet MS", "Segoe UI", sans-serif;
				background: #f5efe2;
				color: #20160f;
			}

			* {
				box-sizing: border-box;
			}

			body {
				margin: 0;
				min-height: 100vh;
				display: grid;
				place-items: center;
				background:
					radial-gradient(circle at top, rgba(240, 146, 56, 0.28), transparent 38%),
					linear-gradient(180deg, #f8f2e8 0%, #efe4cf 100%);
			}

			main {
				width: min(680px, calc(100vw - 2rem));
				padding: 3rem;
				border-radius: 24px;
				background: rgba(255, 250, 242, 0.9);
				box-shadow: 0 24px 80px rgba(66, 39, 17, 0.14);
				text-align: center;
			}

			h1 {
				margin: 0;
				font-size: clamp(3rem, 10vw, 5rem);
				letter-spacing: -0.06em;
			}

			.video-embed {
				width: 100%;
				max-width: min(100%, 560px);
				margin: 1.25rem auto 0;
				aspect-ratio: 16 / 9;
				border-radius: 14px;
				overflow: hidden;
				box-shadow: 0 12px 40px rgba(66, 39, 17, 0.12);
				background: #1a1410;
			}

			.video-embed iframe {
				display: block;
				width: 100%;
				height: 100%;
				border: 0;
			}

			p {
				margin: 1rem 0 0;
				font-size: clamp(1rem, 2vw, 1.25rem);
				line-height: 1.6;
			}

			.legal-links {
				margin-top: 1.75rem;
				font-size: clamp(0.9rem, 1.8vw, 1.05rem);
			}

			a {
				color: #8c3b0a;
				font-weight: 700;
			}

			.download-wrap {
				margin-top: 1.5rem;
				display: flex;
				flex-direction: column;
				align-items: center;
				gap: 0.65rem;
			}

			.download-version {
				margin: 0;
				font-size: clamp(0.95rem, 1.6vw, 1.1rem);
				font-weight: 600;
				color: #5a3d28;
			}

			.download-dropdown {
				position: relative;
				display: inline-block;
				text-align: left;
			}

			.download-dropdown summary {
				list-style: none;
				cursor: pointer;
				padding: 0.65rem 1.15rem;
				border-radius: 12px;
				background: linear-gradient(180deg, #e8a55a 0%, #d47a28 100%);
				color: #fff;
				font-weight: 700;
				font-size: 1rem;
				border: 1px solid rgba(140, 59, 10, 0.35);
				box-shadow: 0 4px 14px rgba(140, 59, 10, 0.25);
				display: inline-flex;
				align-items: center;
				gap: 0.5rem;
			}

			.download-dropdown summary::-webkit-details-marker {
				display: none;
			}

			.download-dropdown summary:focus-visible {
				outline: 2px solid #8c3b0a;
				outline-offset: 3px;
			}

			.download-dropdown .caret {
				display: inline-block;
				transition: transform 0.15s ease;
				font-size: 0.75em;
			}

			.download-dropdown[open] .caret {
				transform: rotate(90deg);
			}

			.download-menu {
				position: absolute;
				left: 50%;
				transform: translateX(-50%);
				margin-top: 0.5rem;
				min-width: 240px;
				padding: 0.35rem;
				border-radius: 12px;
				background: rgba(255, 250, 242, 0.98);
				border: 1px solid rgba(140, 59, 10, 0.2);
				box-shadow: 0 12px 40px rgba(66, 39, 17, 0.15);
				z-index: 1;
			}

			.download-menu a {
				display: block;
				padding: 0.55rem 0.75rem;
				border-radius: 8px;
				text-decoration: none;
				color: #8c3b0a;
				font-weight: 700;
				font-size: 0.95rem;
			}

			.download-menu a:hover,
			.download-menu a:focus-visible {
				background: rgba(240, 146, 56, 0.18);
				outline: none;
			}
		</style>
	</head>
	<body>
		<main>
			<h1>BlockChat</h1>
			<div class="video-embed">
				<iframe
					src="https://www.youtube.com/embed/${HOMEPAGE_YOUTUBE_VIDEO_ID}"
					title="BlockChat — YouTube video"
					allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
					allowfullscreen
					loading="lazy"
					referrerpolicy="strict-origin-when-cross-origin"
				></iframe>
			</div>
			<p>${HOMEPAGE_DESCRIPTION}</p>
			<div class="download-wrap">
				<p class="download-version">Minecraft Java Edition 1.21.11 · BlockChat ${modVersionHtml}</p>
				<details class="download-dropdown">
					<summary>
						Download BlockChat
						<span class="caret" aria-hidden="true">▸</span>
					</summary>
					<div class="download-menu">
						<a href="${winUrl}" target="_blank" rel="noopener noreferrer">Windows</a>
						<a href="${armUrl}" target="_blank" rel="noopener noreferrer">MacOS Apple Silicon</a>
						<a href="${amdUrl}" target="_blank" rel="noopener noreferrer">MacOS Intel</a>
					</div>
				</details>
			</div>
			<p>
				<a href="https://discord.gg/hyYnRARfHE" target="_blank" rel="noopener noreferrer">Join the Discord</a>
				for support.
			</p>
			<p>
				Created by
				<a href="https://youtube.com/@DesertReet" target="_blank" rel="noreferrer">Desert Reet</a>
			</p>
			<p class="legal-links">
				<a href="/privacy-policy">Privacy Policy</a>
				<span aria-hidden="true"> · </span>
				<a href="/terms-of-service">Terms of Service</a>
			</p>
		</main>
	</body>
</html>`;
}

/**
 * Serves the public landing page with download links resolved from the latest GitHub release.
 */
export async function handleHomepage(env: Env): Promise<Response> {
	try {
		const links = await getHomepageDownloadLinks(env);
		const html = buildHomepageHtml(links);
		return new Response(html, {
			headers: { 'content-type': 'text/html;charset=UTF-8' },
		});
	} catch {
		const html = buildHomepageHtml(FALLBACK_DOWNLOAD_LINKS);
		return new Response(html, {
			headers: { 'content-type': 'text/html;charset=UTF-8' },
		});
	}
}
