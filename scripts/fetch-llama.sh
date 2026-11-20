#!/usr/bin/env bash
# Pinned llama.cpp sources for native builds — reproducible commit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"
PIN="$ROOT/vendor/LLAMA_CPP_COMMIT"
REPO="https://github.com/ggml-org/llama.cpp.git"
REV="$(tr -d '[:space:]' < "$PIN")"
if [[ -z "$REV" ]]; then
  echo "missing llama.cpp revision in $PIN" >&2
  exit 1
fi
mkdir -p "$ROOT/third_party"
if [[ -d "$DEST/.git" ]]; then
  current="$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)"
  if [[ "$current" == "$REV" ]]; then
    echo "llama.cpp at $DEST $current"
    exit 0
  fi
  git -C "$DEST" fetch --depth 1 origin "$REV" 2>/dev/null || git -C "$DEST" fetch origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
else
  rm -rf "$DEST"
  git init -q "$DEST"
  git -C "$DEST" remote add origin "$REPO"
  git -C "$DEST" fetch --depth 1 origin "$REV" 2>/dev/null || git -C "$DEST" fetch origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
fi
echo "llama.cpp at $DEST $(git -C "$DEST" rev-parse HEAD)"
