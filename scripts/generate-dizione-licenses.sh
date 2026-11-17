#!/usr/bin/env bash
# Regenerate licenses/hanami_dizione-rust-THIRD_PARTY.md from Cargo.lock.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRATE="$ROOT/app/src/main/rust/hanami_dizione"
OUT="$ROOT/licenses/hanami_dizione-rust-THIRD_PARTY.md"
if [[ ! -d "$ROOT/vendor/voca/crates/dizione" ]]; then
  bash "$ROOT/scripts/fetch-voca.sh"
fi
if ! command -v cargo-about >/dev/null 2>&1; then
  echo "cargo-about not installed; run: cargo install cargo-about --version 0.6.6 --locked" >&2
  exit 1
fi
(
  cd "$CRATE"
  cargo about generate --locked -o "$OUT" "$ROOT/licenses/hanami_dizione-about.hbs"
)
echo "wrote $OUT"