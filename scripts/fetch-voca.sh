#!/usr/bin/env bash
# Pinned voca (dizione) sources for hanami_dizione JNI — Apache-2.0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/voca"
PIN="$ROOT/vendor/VOCA_COMMIT"
REPO="https://github.com/sultson/voca.git"
REV="$(tr -d '[:space:]' < "$PIN")"
if [[ -z "$REV" ]]; then
  echo "missing voca revision in $PIN" >&2
  exit 1
fi
mkdir -p "$ROOT/vendor"
if [[ -d "$DEST/.git" ]]; then
  current="$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)"
  if [[ "$current" == "$REV" ]]; then
    echo "voca at $DEST $current"
    exit 0
  fi
  git -C "$DEST" fetch --depth 1 origin "$REV" 2>/dev/null || git -C "$DEST" fetch origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
else
  rm -rf "$DEST"
  git clone "$REPO" "$DEST"
  git -C "$DEST" fetch --depth 1 origin "$REV" 2>/dev/null || git -C "$DEST" fetch origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
fi
echo "voca at $DEST $(git -C "$DEST" rev-parse HEAD)"