# BlockChat

### [Download this mod on Modrinth](https://modrinth.com/project/block-chat)

## Overview

BlockChat is a Snapchat-style Minecraft social mod. Players open the UI with `U`, capture clips with `J`, sign in through Microsoft device-code auth, and exchange screenshots or videos with friends.


[![Watch the BlockChat trailer](https://img.youtube.com/vi/PGMen8-84xw/maxresdefault.jpg)](https://www.youtube.com/watch?v=PGMen8-84xw)

_Click the image above to watch!_


This repo splits BlockChat into two standalone projects:

- [`client/`](./client/README.md): the Fabric client mod for Minecraft 1.21.11
- [`server/`](./server/README.md): the Cloudflare Workers backend that handles auth, WebSocket messaging, Durable Objects, and media storage


## Quick Start

### Client

```bash
cd client
./gradlew runClient
```

Build distributable jars:

```bash
cd client
./gradlew build
```

See [`client/README.md`](./client/README.md) for requirements, native helper notes, and packaging details.

### Server

```bash
cd server
npm install
npm test
npm run dev
```

Deploy with Wrangler when your Cloudflare bindings are configured:

```bash
cd server
npm run deploy
```

See [`server/README.md`](./server/README.md) for environment setup, Cloudflare bindings, and test/deploy notes.

## Hosted backend

If you use the BlockChat backend services hosted by Desert Reet (the default backend url at https://blockchat.desertreet.com), you agree to the [Terms of Service](https://blockchat.desertreet.com/terms-of-service) and [Privacy Policy](https://blockchat.desertreet.com/privacy-policy).

## Support

Need help or want to report an issue with the live project? Join the [BlockChat Discord](https://discord.gg/invite/hyYnRARfHE).
