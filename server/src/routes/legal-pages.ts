/**
 * Serves static HTML for BlockChat legal documents (privacy policy, terms of service, and license).
 */

const LEGAL_STYLES = `
:root {
	color-scheme: light;
	font-family: "Trebuchet MS", "Segoe UI", sans-serif;
	background: #f5efe2;
	color: #20160f;
	line-height: 1.65;
}
* { box-sizing: border-box; }
body {
	margin: 0;
	min-height: 100vh;
	background:
		radial-gradient(circle at top, rgba(240, 146, 56, 0.28), transparent 38%),
		linear-gradient(180deg, #f8f2e8 0%, #efe4cf 100%);
}
.wrap {
	width: min(720px, calc(100vw - 2rem));
	margin: 2rem auto 3rem;
	padding: 2rem 2.25rem 2.5rem;
	border-radius: 24px;
	background: rgba(255, 250, 242, 0.95);
	box-shadow: 0 24px 80px rgba(66, 39, 17, 0.14);
}
nav {
	margin-bottom: 1.5rem;
	font-size: 0.95rem;
}
nav a { color: #8c3b0a; font-weight: 700; text-decoration: none; }
nav a:hover { text-decoration: underline; }
h1 {
	margin: 0 0 0.75rem;
	font-size: clamp(1.75rem, 4vw, 2.25rem);
	letter-spacing: -0.04em;
}
.tldr {
	margin: 0 0 2rem;
	padding: 1.25rem 1.35rem;
	border-radius: 16px;
	border: 1px solid rgba(140, 59, 10, 0.22);
	background: rgba(240, 146, 56, 0.12);
}
.tldr strong { display: block; margin-bottom: 0.5rem; font-size: 1.05rem; }
.tldr p { margin: 0; font-size: 1rem; }
.meta { margin: 0 0 1.75rem; font-size: 0.9rem; color: #5c4a3d; }
section { margin-top: 1.75rem; }
section:first-of-type { margin-top: 0; }
h2 {
	margin: 0 0 0.65rem;
	font-size: 1.15rem;
	letter-spacing: -0.02em;
}
p { margin: 0 0 0.85rem; }
p:last-child { margin-bottom: 0; }
ul { margin: 0 0 0.85rem; padding-left: 1.35rem; }
li { margin-bottom: 0.4rem; }
a { color: #8c3b0a; font-weight: 600; }
`;

/**
 * @param extraNav - When set, renders the top nav (home + cross-links). Omitted for pages that should have no header bar (e.g. license).
 */
function legalPageShell(title: string, bodyInner: string, extraNav?: string): string {
	const navBlock =
		extraNav !== undefined
			? `<nav><a href="/">← BlockChat home</a>${extraNav}</nav>
		`
			: '';
	return `<!doctype html>
<html lang="en">
<head>
	<meta charset="utf-8" />
	<meta name="viewport" content="width=device-width, initial-scale=1" />
	<title>${title}</title>
	<link rel="icon" href="/favicon.png" type="image/png" />
	<style>${LEGAL_STYLES}</style>
</head>
<body>
	<div class="wrap">
		${navBlock}${bodyInner}
	</div>
</body>
</html>`;
}

const PRIVACY_BODY = `
<h1>Privacy Policy</h1>
<p class="meta">Last updated: April 1, 2026</p>

<div class="tldr">
	<strong>TL;DR</strong>
	<p>
		We only keep what we need to run BlockChat. We do not sell your data, use it to train AI models,
		or share it with advertisers. Photos and videos you send are removed from our systems after the
		recipient has opened them, subject to short technical retention described below. We use standard
		cloud infrastructure to host the service.
	</p>
</div>

<section>
	<h2>1. Who this applies to</h2>
	<p>
		This Privacy Policy describes how BlockChat (“we”, “us”) handles information when you use the
		BlockChat service, including our website, APIs, and related Minecraft mod experience. By using
		BlockChat, you agree to this policy.
	</p>
</section>

<section>
	<h2>2. Information we collect</h2>
	<p>We collect only what is necessary to operate the product:</p>
	<ul>
		<li>
			<strong>Account and identity.</strong> Information needed to sign you in and associate your
			activity with your Minecraft account (for example, identifiers and profile details we obtain
			through the normal Minecraft / Microsoft sign-in flow).
		</li>
		<li>
			<strong>Social graph.</strong> Friend relationships, friend requests, and related metadata
			needed to deliver messages between users you choose to connect with.
		</li>
		<li>
			<strong>Messages and media.</strong> Content you send through BlockChat, including uploads
			associated with snaps (images and videos), captions where you provide them, and delivery
			status needed to show what was sent, received, or opened.
		</li>
		<li>
			<strong>Technical data.</strong> Limited operational data such as timestamps, device or client
			hints needed for reliability, abuse prevention, rate limiting, and debugging (for example,
			request metadata our servers naturally receive).
		</li>
	</ul>
	<p>We do not ask for unrelated personal details, and we do not require real-world contact information to use BlockChat beyond what your chosen sign-in method provides.</p>
</section>

<section>
	<h2>3. How we use information</h2>
	<p>We use the information above only to:</p>
	<ul>
		<li>Provide, maintain, and improve BlockChat (delivery, inbox, notifications, and account features).</li>
		<li>Authenticate you and prevent unauthorized access.</li>
		<li>Detect, prevent, and respond to abuse, spam, fraud, or violations of our Terms of Service.</li>
		<li>Comply with applicable law or enforceable legal process when required.</li>
	</ul>
	<p>
		We do <strong>not</strong> use your content or personal information to train machine learning or
		AI models. We do <strong>not</strong> sell your personal information. We do <strong>not</strong>
		share your data with third parties for their own marketing purposes.
	</p>
</section>

<section>
	<h2>4. Media retention (snaps)</h2>
	<p>
		BlockChat is designed so that sent images and videos are not kept indefinitely. After a
		recipient opens a snap, associated media is deleted from our storage as part of normal operation.
		There may be a short period where backups or internal queues still contain fragments of data
		before deletion completes; we do not use that window for unrelated processing.
	</p>
	<p>
		If a snap expires before it is opened, or is otherwise removed according to product rules, we
		delete or make it unavailable consistent with how the service is built. Exact timing can depend
		on technical factors (for example, upload lifecycle and storage reconciliation), but the intent
		is minimal retention aligned with the product description above.
	</p>
</section>

<section>
	<h2>5. Service providers and infrastructure</h2>
	<p>
		We host BlockChat on cloud infrastructure (for example, compute and object storage) operated by
		providers such as Cloudflare. Those providers process data only as needed to run the service
		under our instructions. They are not permitted to use your personal information for their own
		purposes beyond providing the service.
	</p>
</section>

<section>
	<h2>6. Legal and safety</h2>
	<p>
		We may preserve or disclose information if we believe in good faith that it is necessary to:
		comply with the law or legal process; protect the safety of any person; address fraud, security,
		or technical issues; or protect our rights or property. We may also share aggregated or
		de-identified information that cannot reasonably identify you.
	</p>
</section>

<section>
	<h2>7. Security</h2>
	<p>
		We use industry-standard measures appropriate to the nature of the service to protect data in
		transit and at rest. No method of transmission or storage is 100% secure; we work to reduce risk
		and respond to issues we become aware of.
	</p>
</section>

<section>
	<h2>8. Children</h2>
	<p>
		BlockChat is not directed at children under the age required by applicable law for standalone
		consent where you live. If you believe we have collected information from a child in error,
		contact us and we will take appropriate steps.
	</p>
</section>

<section>
	<h2>9. International users</h2>
	<p>
		Our infrastructure may process and store data in countries other than your own. By using
		BlockChat, you understand that your information may be transferred to and processed in those
		locations, subject to this policy and applicable law.
	</p>
</section>

<section>
	<h2>10. Your choices and rights</h2>
	<p>
		Depending on where you live, you may have rights to access, correct, delete, or export certain
		personal information, or to object to or limit certain processing. You can exercise choices
		through in-product settings where available, or by contacting us. We will respond consistent
		with applicable law.
	</p>
</section>

<section>
	<h2>11. Changes to this policy</h2>
	<p>
		We may update this Privacy Policy from time to time. We will post the updated version on this
		page and adjust the “Last updated” date. Continued use of BlockChat after changes means you
		accept the revised policy, except where a stricter consent requirement applies under law.
	</p>
</section>

<section>
	<h2>12. Contact</h2>
	<p>
		For questions, contact Desert Reet on 
		<a href="https://discord.gg/hyYnRARfHE" target="_blank" rel="noreferrer">Discord</a>.
	</p>
</section>
`;

const TERMS_BODY = `
<h1>Terms of Service</h1>
<p class="meta">Last updated: April 2, 2026</p>

<div class="tldr">
	<strong>TL;DR</strong>
	<p>
		Use BlockChat only as intended: capture and send screenshots and videos from inside Minecraft to
		your friends. Do not hack or misuse the mod or our servers. Do not spam, bypass
		rate limits, or upload media that was not captured from Minecraft. We may suspend accounts that
		break these rules or harm the service.
	</p>
</div>

<section>
	<h2>1. Agreement</h2>
	<p>
		These Terms of Service (“Terms”) govern your access to and use of BlockChat, including our
		website, APIs, WebSocket endpoints, and the BlockChat Minecraft mod (together, the “Service”).
		By using the Service, you agree to these Terms. If you do not agree, do not use BlockChat.
	</p>
</section>

<section>
	<h2>2. The Service</h2>
	<p>
		BlockChat provides a social layer for Minecraft that lets you send screenshots and videos to
		people you connect with through the product. Features, availability, and behavior may change as
		we develop the Service. We may modify, suspend, or discontinue any part of the Service with or
		without notice where permitted by law.
	</p>
</section>

<section>
	<h2>3. Permitted use</h2>
	<p>You may use BlockChat only for its intended purpose:</p>
	<p>
		You are solely responsible for all media that you send through BlockChat, including making sure
		you have the right to send it and that it complies with these Terms and applicable law.
	</p>
	<ul>
		<li>
			Capturing and sending <strong>screenshots and videos from within Minecraft</strong> to friends
			you connect with through BlockChat, using the official mod and supported clients as we provide
			them.
		</li>
		<li>
			Using the Service in compliance with these Terms, our Privacy Policy, and applicable law.
		</li>
	</ul>
	<p>
		Any other use of the mod, our software, or our infrastructure—including repurposing the mod for
		unrelated automation, scraping, or integration not expressly allowed by us—is outside permitted
		use unless we give you written permission.
	</p>
</section>

<section>
	<h2>4. Prohibited conduct</h2>
	<p>You must not:</p>
	<ul>
		<li>
			<strong>Modify or hack</strong> the BlockChat mod, client, or our servers to
			bypass security, extract secrets, interfere with other users, or gain unauthorized access.
		</li>
		<li>
			<strong>Send media not from Minecraft</strong> through paths meant only for in-game capture,
			or otherwise misrepresent the origin of content (for example, uploading files that were not
			produced by the supported capture flow).
		</li>
		<li>
			<strong>Spam or abuse</strong> the Service, including flooding friends with unwanted messages,
			harassing users, or generating excessive load.
		</li>
		<li>
			<strong>Bypass or defeat rate limits, quotas, or technical controls</strong> we put in place
			to keep the Service fair and stable—including using scripted traffic or
			exploits to evade limits.
		</li>
		<li>
			Use the Service for unlawful activity, to distribute malware, or to share content that is
			illegal, infringes others’ rights, or violates platform rules where applicable.
		</li>
		<li>
			Impersonate others, misrepresent your identity, or attempt to access another user’s account
			or data without permission.
		</li>
		<li>
			Interfere with or disrupt the Service, our providers, or other users (including denial-of-
			service style attacks).
		</li>
	</ul>
</section>

<section>
	<h2>5. Enforcement</h2>
	<p>
		We may investigate suspected violations. We may take action we consider appropriate, including
		warning you, removing content, rate-limiting or throttling your access, temporarily or permanently
		suspending your account or device identifiers, or terminating your access to the Service. We may
		do so with or without prior notice where we believe it is necessary to protect the Service or
		other users, or as required by law.
	</p>
	<p>
		You understand that repeated or serious violations (including spam, bypassing limits, or
		attempting to send non-Minecraft media through restricted channels) may result in suspension or
		a permanent ban from the platform.
	</p>
</section>

<section>
	<h2>6. Minecraft and third-party terms</h2>
	<p>
		BlockChat is an independent project and is not endorsed by or affiliated with Mojang, Microsoft,
		or Minecraft. Your use of Minecraft and Microsoft services remains subject to their respective
		terms and policies. You are responsible for complying with them in addition to these Terms.
	</p>
</section>

<section>
	<h2>7. Intellectual property</h2>
	<p>
		The Service, including the BlockChat name, branding, mod, and server-side software, is owned by us
		or our licensors. We grant you a limited, revocable, non-exclusive license to use the Service
		only as permitted by these Terms. You retain rights to content you create; by using the Service
		you grant us the rights reasonably necessary to operate BlockChat (for example, to store,
		transmit, and display your snaps to recipients you choose).
	</p>
</section>

<section>
	<h2>8. Disclaimers</h2>
	<p>
		THE SERVICE IS PROVIDED “AS IS” AND “AS AVAILABLE” WITHOUT WARRANTIES OF ANY KIND, WHETHER
		EXPRESS OR IMPLIED, INCLUDING MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND
		NON-INFRINGEMENT, TO THE MAXIMUM EXTENT PERMITTED BY LAW. WE DO NOT WARRANT THAT THE SERVICE WILL
		BE UNINTERRUPTED, ERROR-FREE, OR FREE OF HARMFUL COMPONENTS.
	</p>
</section>

<section>
	<h2>9. Limitation of liability</h2>
	<p>
		TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, WE AND OUR AFFILIATES, CONTRIBUTORS, AND
		SERVICE PROVIDERS WILL NOT BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR
		PUNITIVE DAMAGES, OR ANY LOSS OF PROFITS, DATA, OR GOODWILL, ARISING FROM YOUR USE OF THE
		SERVICE. OUR TOTAL LIABILITY FOR ANY CLAIM ARISING OUT OF THESE TERMS OR THE SERVICE IS LIMITED
		TO THE GREATER OF (A) THE AMOUNT YOU PAID US FOR THE SERVICE IN THE TWELVE MONTHS BEFORE THE
		CLAIM (IF ANY), OR (B) FIFTY U.S. DOLLARS (US $50), EXCEPT WHERE PROHIBITED BY LAW.
	</p>
</section>

<section>
	<h2>10. Indemnity</h2>
	<p>
		You agree to defend and indemnify us and our affiliates against claims, damages, losses, and
		expenses (including reasonable attorneys’ fees) arising from your use of the Service, your
		content, or your violation of these Terms or applicable law, to the extent permitted by law.
	</p>
</section>

<section>
	<h2>11. Changes to these Terms</h2>
	<p>
		We may update these Terms from time to time. We will post the updated version on this page and
		change the “Last updated” date. If changes are material, we may provide additional notice where
		practical. Continued use after changes become effective constitutes acceptance of the revised
		Terms, except where applicable law requires a different process.
	</p>
</section>

<section>
	<h2>12. Termination</h2>
	<p>
		You may stop using the Service at any time. We may suspend or terminate your access under these
		Terms. Provisions that by their nature should survive (including intellectual property,
		disclaimers, limitation of liability, and dispute terms) will survive termination.
	</p>
</section>

<section>
	<h2>13. Governing law</h2>
	<p>
		These Terms are governed by applicable law, without regard to conflict-of-law principles,
		except where mandatory consumer protections in your country require otherwise. Venue for disputes
		lies in the courts that have jurisdiction over the matter under those laws, where permitted.
	</p>
</section>

<section>
	<h2>14. Contact</h2>
	<p>
		For questions, contact Desert Reet on 
		<a href="https://discord.gg/hyYnRARfHE" target="_blank" rel="noreferrer">Discord</a>.
	</p>
</section>
`;

const NAV_TO_TERMS =
	' <span aria-hidden="true">·</span> <a href="/terms-of-service">Terms of Service</a>';
const NAV_TO_PRIVACY =
	' <span aria-hidden="true">·</span> <a href="/privacy-policy">Privacy Policy</a>';
const NAV_TO_LICENSE =
	' <span aria-hidden="true">·</span> <a href="/license">License</a>';

const PRIVACY_HTML = legalPageShell(
	'Privacy Policy — BlockChat',
	PRIVACY_BODY,
	NAV_TO_TERMS + NAV_TO_LICENSE,
);
const TERMS_HTML = legalPageShell(
	'Terms of Service — BlockChat',
	TERMS_BODY,
	NAV_TO_PRIVACY + NAV_TO_LICENSE,
);

const LICENSE_BODY = `
<h1>MIT License</h1>

<p>Copyright (c) 2026 Desert Reet</p>

<p>
	Permission is hereby granted, free of charge, to any person obtaining a copy of this
	software and associated documentation files (the "Software"), to deal in the Software
	without restriction, including without limitation the rights to use, copy, modify, merge,
	publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
	to whom the Software is furnished to do so, subject to the following conditions:
</p>

<p>
	The above copyright notice and this permission notice shall be included in all copies or
	substantial portions of the Software.
</p>

<p>
	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
	INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
	PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
	FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
	OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
	DEALINGS IN THE SOFTWARE.
</p>
`;

const LICENSE_HTML = legalPageShell('MIT License — BlockChat', LICENSE_BODY);

/** Returns the Privacy Policy page (HTML). */
export function handlePrivacyPolicy(): Response {
	return new Response(PRIVACY_HTML, {
		headers: { 'content-type': 'text/html;charset=UTF-8' },
	});
}

/** Returns the Terms of Service page (HTML). */
export function handleTermsOfService(): Response {
	return new Response(TERMS_HTML, {
		headers: { 'content-type': 'text/html;charset=UTF-8' },
	});
}

/** Returns the MIT license page (HTML). */
export function handleLicense(): Response {
	return new Response(LICENSE_HTML, {
		headers: { 'content-type': 'text/html;charset=UTF-8' },
	});
}
