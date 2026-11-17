#!/usr/bin/env bash
# Pinned voca (dizione) sources for hanami_dizione JNI — Apache-2.0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/voca"
PIN="$ROOT/vendor/VOCA_COMMIT"
REV="$(tr -d '[:space:]' < "$PIN")"
mkdir -p "$ROOT/vendor"
if [[ ! -d "$DEST/.git" ]]; then
  git clone https://github.com/sultson/voca.git "$DEST"
fi
git -C "$DEST" fetch --depth 1 origin "$REV" 2>/dev/null || git -C "$DEST" fetch origin "$REV"
git -C "$DEST" checkout --detach "$REV"
echo "voca at $DEST $(git -C "$DEST" rev-parse HEAD)"