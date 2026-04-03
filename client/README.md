# BlockChat Client

Standalone Fabric client mod for BlockChat on Minecraft Java Edition `1.21.11`.

## What This Project Does

The client mod provides:

- the BlockChat UI, opened with `U`
- screenshot and video capture flow, with recording toggled by `J`
- Microsoft device-code sign-in
- friend, chat, inbox, and snap-composer UI
- bundled native helpers and FFmpeg packaging for supported platforms

## Requirements

- Java 21
- a working Gradle environment via the included wrapper
- macOS for building the macOS native helper
- Windows with Visual Studio Build Tools for building the Windows native helper

Normal UI or networking work can be done without rebuilding both native helpers, but packaging platform-complete jars depends on the relevant helper artifacts being available.

## Development

Run a development Minecraft client:

```bash
./gradlew runClient
```

Build distributable jars:

```bash
./gradlew build
```

The main outputs land in `build/libs/`:

- `desertreet-blockchat-<version>-macos-arm64.jar`
- `desertreet-blockchat-<version>-macos-amd64.jar`
- `desertreet-blockchat-<version>-windows.jar`

The build downloads FFmpeg bundles into `native/ffmpeg-cache/` when they are missing. If the cache is already populated, Gradle reuses it.

## Native Helpers

### macOS

The macOS helper build script lives in [`native/macos-capture-helper/`](./native/macos-capture-helper/README.md).

On macOS hosts, the Gradle build automatically runs `native/macos-capture-helper/build-helper.sh` during client resource processing when needed.

### Windows

The Windows helper sources live in [`native/windows-capture-helper/`](./native/windows-capture-helper/README.md).

The executable is only built on Windows. If you want a helper-backed Windows jar, build the helper there first with:

```bat
native\windows-capture-helper\build-helper.cmd
```

## Project Layout

- `src/client/java/`: client-only mod code
- `src/client/resources/`: mixins, lang, and client resources
- `src/main/resources/`: Fabric metadata and icon
- `native/`: platform-specific capture helpers and FFmpeg cache
