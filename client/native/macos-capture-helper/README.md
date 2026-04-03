# BlockChat macOS ScreenCaptureKit Capture Helper

This helper provides the macOS-native BlockChat capture path for:

- Minecraft window video through ScreenCaptureKit recording output
- system audio through ScreenCaptureKit
- microphone audio through ScreenCaptureKit's microphone output

It is intentionally isolated from the Java mod so the shared recorder wiring can remain platform-agnostic.

## Build

```bash
cd modules/blockchat/native/macos-capture-helper
./build-helper.sh
```

The release binary is written to:

```text
.build/release/BlockChatMacCaptureHelper
```

## Permissions

The first run requires macOS privacy permissions for:

- `Screen & System Audio Recording`
- `Microphone`

For local development, code signing is not required. For distribution, a signed and ideally notarized helper is recommended so Gatekeeper and TCC prompts are smoother for users.

## Current Scope

- Records a ScreenCaptureKit H.264 MP4 for the Minecraft capture target.
- Records system audio to a WAV sidecar.
- Records microphone audio to a separate WAV sidecar when enabled.
- Exits cleanly on `SIGINT` / `SIGTERM`.

The Java bridge starts and stops this helper; shared recorder integration lives outside this helper directory.
