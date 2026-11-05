#!/usr/bin/env bash
# Deterministic JNI instrumentation GGUF fixture: downloaded on the host,
# verified before Gradle packages androidTest assets, never from the emulator.
set -Eeuo pipefail

readonly FILE_NAME="stories15M-q4_0.gguf"
readonly REVISION="499bc8821c6b12b4e53c5bffcb21ec206f212d81"
readonly EXPECTED_SHA256="66967fbece6dbe97886593fdbb73589584927e29119ec31f08090732d1861739"
readonly URL="https://huggingface.co/ggml-org/models-moved/resolve/${REVISION}/tinyllamas/${FILE_NAME}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly FIXTURE_DIR="${REPO_ROOT}/app/src/androidTest/assets"
readonly FIXTURE_PATH="${FIXTURE_DIR}/${FILE_NAME}"

verify_fixture() {
  local file="$1"
  test -f "$file" || { echo "Missing fixture: $file" >&2; return 1; }
  printf '%s  %s\n' "$EXPECTED_SHA256" "$file" | sha256sum --check --status ||
    { echo "Smoke GGUF checksum mismatch: $file" >&2; return 1; }
  local size
  size="$(wc -c < "$file")"
  if [[ "$size" -ne 19077344 ]]; then
    echo "Unexpected smoke GGUF size: $size bytes" >&2
    return 1
  fi
}

if [[ "${1:-}" == "--verify" ]]; then
  verify_fixture "${2:-$FIXTURE_PATH}"
  echo "Smoke GGUF SHA256 verified"
  exit 0
fi
if [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--verify [FILE]]" >&2
  exit 2
fi

mkdir -p "$FIXTURE_DIR"
if [[ -f "$FIXTURE_PATH" ]]; then
  # A poisoned cache is a hard error, never an implicit re-download.
  verify_fixture "$FIXTURE_PATH"
  echo "Using verified smoke GGUF fixture from local cache"
  exit 0
fi

temporary="$(mktemp "${FIXTURE_PATH}.part.XXXXXX")"
trap 'rm -f "$temporary"' EXIT

curl --fail --location --retry 3 --connect-timeout 20 --max-time 300 \
     --output "$temporary" "$URL"
verify_fixture "$temporary"
mv "$temporary" "$FIXTURE_PATH"
echo "Provisioned SHA256-verified smoke GGUF to $FIXTURE_PATH"
