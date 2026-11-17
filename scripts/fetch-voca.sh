#!/usr/bin/env bash
# Vendored phonemizer + optional native TTS for Settings → English tiny-voca (Apache-2.0).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/voca"
mkdir -p "$ROOT/third_party"
if [[ -d "$DEST/crates/dizione" ]]; then
  echo "voca already present at $DEST"
  exit 0
fi
git clone --depth 1 https://github.com/sultson/voca.git "$DEST"