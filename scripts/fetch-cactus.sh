#!/usr/bin/env bash
# Pinned Cactus Android arm64-v8a runtime artifacts for Whistle STT.
# Downloads cactus.h + libcactus.a into third_party/cactus/arm64-v8a/ and verifies SHA-256.
# Binaries stay out of git (third_party/ is gitignored); CI/release must run this before phone builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN_FILE="$ROOT/vendor/CACTUS_RELEASE"
SUMS_FILE="$ROOT/vendor/CACTUS_ANDROID_ARM64.sha256"
DEST="$ROOT/third_party/cactus/arm64-v8a"
VERSION="$(tr -d '[:space:]' < "$PIN_FILE")"
if [[ -z "$VERSION" ]]; then
  echo "missing Cactus release pin in $PIN_FILE" >&2
  exit 1
fi
if [[ ! -f "$SUMS_FILE" ]]; then
  echo "missing Cactus checksums in $SUMS_FILE" >&2
  exit 1
fi

BASE="https://huggingface.co/Cactus-Compute/cactus/resolve/main/releases/${VERSION}/android-arm64"
mkdir -p "$DEST"

need_fetch=0
if [[ ! -f "$DEST/cactus.h" || ! -f "$DEST/libcactus.a" ]]; then
  need_fetch=1
elif ! (cd "$DEST" && sha256sum -c "$SUMS_FILE" >/dev/null 2>&1); then
  need_fetch=1
fi

if [[ "$need_fetch" -eq 1 ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "Fetching Cactus ${VERSION} android-arm64 into $DEST"
  curl -fsSL -o "$tmp/cactus.h" "${BASE}/cactus.h"
  curl -fsSL -o "$tmp/libcactus.a" "${BASE}/libcactus.a"
  (cd "$tmp" && sha256sum -c "$SUMS_FILE")
  install -m 0644 "$tmp/cactus.h" "$DEST/cactus.h"
  install -m 0644 "$tmp/libcactus.a" "$DEST/libcactus.a"
fi

(cd "$DEST" && sha256sum -c "$SUMS_FILE")
echo "Cactus ${VERSION} android-arm64 ready at $DEST"
