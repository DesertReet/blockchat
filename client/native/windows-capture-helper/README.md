# BlockChat Windows Capture Helper

This directory contains the native Windows helper that records BlockChat clips directly into a final MP4.

## What Lives Here

- `build-helper.cmd`
  - Windows build entrypoint that uses Visual Studio 2022 / Build Tools, `VsDevCmd.bat`, and `msbuild`
- `BlockChatWindowsCaptureHelper/BlockChatWindowsCaptureHelper.vcxproj`
  - native project file
- `BlockChatWindowsCaptureHelper/main.cpp`
  - helper implementation
- `.build/Release/BlockChatWindowsCaptureHelper.exe`
  - built helper artifact consumed by the Java-side BlockChat launcher

## Runtime Contract

The helper is launched by `WindowsNativeCaptureHelperSession` and is expected to:

- parse the existing Java-side args
- capture the Minecraft top-level `HWND` through `Windows.Graphics.Capture`
- encode the Minecraft client area rather than the full decorated window frame, so windowed mode excludes the title bar while borderless/fullscreen still capture the full game image
- enable cursor capture
- capture default-endpoint WASAPI loopback plus optional microphone audio
- use both the saved Java mixer id and display name as best-effort microphone selectors
- avoid mixing ahead of a still-running microphone stream so late mic packets still land in the final MP4
- mix audio to a common `48 kHz` stereo stream
- write the authoritative initial H.264/AAC MP4 through `IMFSinkWriter`
- write the ready file only after real startup is complete
- stop when the stop file appears, finalize the MP4, and exit cleanly
- produce the final Windows recording directly, with no Java-side audio sidecars or FFmpeg remux step on the helper path

## Building

Requirements:

- Visual Studio 2022 or Build Tools with `Desktop development with C++`
- a current Windows SDK

Build command:

```bat
build-helper.cmd
```

Successful output:

- `.build/Release/BlockChatWindowsCaptureHelper.exe`

## Packaging And Fallback

- The BlockChat jar packages this directory into `blockchat-native/windows-capture-helper`.
- If the built helper executable is present, BlockChat uses the helper-owned native MP4 path directly.
- If the executable is absent but helper build resources are available, BlockChat can still use the Windows helper path after a local helper build.
- If neither the executable nor build resources are available, Windows recording is unavailable until the helper is bundled or built.
