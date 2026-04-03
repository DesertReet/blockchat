#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

OUT_DIR="$SCRIPT_DIR/.build/release"
HOME_DIR="$SCRIPT_DIR/.swift-home"
CLANG_CACHE_DIR="$SCRIPT_DIR/.swift-clang-cache"

# Swift module caches embed absolute paths, so a renamed helper directory needs a fresh cache.
rm -rf "$CLANG_CACHE_DIR"
mkdir -p "$OUT_DIR" "$HOME_DIR" "$CLANG_CACHE_DIR"

HOME="$HOME_DIR" \
CLANG_MODULE_CACHE_PATH="$CLANG_CACHE_DIR" \
swiftc \
  -parse-as-library \
  -O \
  -o "$OUT_DIR/BlockChatMacCaptureHelper" \
  "$SCRIPT_DIR/Sources/BlockChatMacCaptureHelper/main.swift" \
  -framework AppKit \
  -framework Foundation \
  -framework AVFoundation \
  -framework AVFAudio \
  -framework ScreenCaptureKit \
  -framework CoreGraphics \
  -framework CoreMedia
