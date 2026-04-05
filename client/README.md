# BlockChat Client

Standalone Fabric client mod for BlockChat on Minecraft Java Edition with a one-branch multi-version build.

## What This Project Does

The client mod provides:

- the BlockChat UI, opened with `U`
- screenshot and video capture flow, with recording toggled by `J`
- Microsoft device-code sign-in
- friend, chat, inbox, and snap-composer UI
- bundled native helpers and FFmpeg packaging for supported platforms

## Requirements

- Java 21 and Java 25 available to Gradle toolchains
- a working Gradle environment via the included wrapper
- macOS for building the macOS native helper
- Windows with Visual Studio Build Tools for building the Windows native helper

Normal UI or networking work can be done without rebuilding both native helpers, but packaging platform-complete jars depends on the relevant helper artifacts being available.

## Development

Run the default development Minecraft client:

```bash
./gradlew runClient
```

List the versions currently enabled by default:

```bash
./gradlew listMinecraftVersions
```

Build one version:

```bash
./gradlew buildVersion -Pblockchat.minecraftVersion=1.21
```

Build all enabled versions:

```bash
./gradlew buildAllVersions
```

The main outputs land in `build/libs/`:

- `desertreet-blockchat-<mod-version>-macos-arm64+<minecraft-version>.jar`
- `desertreet-blockchat-<mod-version>-macos-amd64+<minecraft-version>.jar`
- `desertreet-blockchat-<mod-version>-windows+<minecraft-version>.jar`

The default enabled matrix comes from `supported_minecraft_versions` in [`client/gradle.properties`](./gradle.properties). Override it per build with `-Pblockchat.activeVersions=1.21,1.21.11,26.1.1`.

The base Minecraft `1.21` dependency is exposed as `1.21` in BlockChat build selectors and jar names, so `buildVersion -Pblockchat.minecraftVersion=1.21` produces `desertreet-blockchat-<mod-version>-<platform>+1.21.jar`.

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

- `src/core/java/`: runtime logic intended to stay version-agnostic
- `src/shared/client/java/`: shared Minecraft/Fabric-facing code for the currently supported adapter family
- `src/shared/client/resources/`: shared mixins, lang, and client resources
- `src/shared/main/resources/`: shared non-versioned main resources
- `versions/<minecraft-version>/`: per-version properties and overrides
- `overlay_from=<minecraft-version>` in `version.properties`: reuse another version's overrides when two patch lines share the same adapter layer
- `native/`: platform-specific capture helpers and FFmpeg cache
