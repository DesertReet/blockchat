# BlockChat Server

Cloudflare Workers backend for BlockChat.

## What This Project Does

The server handles:

- Microsoft device-code auth start and verify routes
- player session issuance
- WebSocket upgrades for real-time BlockChat events
- one Durable Object per player for social state and snap state
- R2 presigned upload/download flows for media
- the public homepage and legal pages at `blockchat.desertreet.com`

Runtime note:

- This project is a web backend only. It has no Minecraft gameplay loop and no active-dimension versus inactive-dimension runtime behavior.

## Requirements

- Node.js 20+ is recommended
- npm
- Cloudflare Wrangler
- a Cloudflare account with the expected Worker, Durable Object, and R2 bindings

## Install

```bash
npm install
```

## Local Development

Run the Worker locally:

```bash
npm run dev
```

Current key routes are:

- `GET /`
- `POST /api/auth/device-code`
- `POST /api/auth/verify`
- `GET /ws`
- `POST /api/test/reset-dos`

The Worker config lives in [`wrangler.jsonc`](./wrangler.jsonc).

## Tests

Run the Vitest suite:

```bash
npm test
```

If you change bindings or generated Worker types, regenerate them with:

```bash
npm run cf-typegen
```

## Deploy

Production:

```bash
npm run deploy
```

Test environment:

```bash
npm run deploy:test
```

The checked-in Wrangler config currently expects:

- `USER_DO` Durable Object binding using `BlockChatUserDurableObject`
- `SNAPS_BUCKET` R2 bucket binding
- `MS_CLIENT_ID` and `MS_TENANT_ID` vars
- a production custom domain for `blockchat.desertreet.com`
- a test custom domain for `blockchat-test.desertreet.com`

`TEST_MODE` is only meant for the test environment. Do not enable it in production.

## Important Client/Server Note

The standalone [`../client`](../client/README.md) project is still pinned to the production BlockChat base URL by default. Running this Worker locally is useful for backend development and testing, but the copied client will not talk to it automatically without additional client-side endpoint changes.

## Related Docs

- Repo overview: [`../README.md`](../README.md)
- Protocol reference: [`./docs/PROTOCOL.md`](./docs/PROTOCOL.md)
