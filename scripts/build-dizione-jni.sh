#!/usr/bin/env bash
# Builds libhanami_dizione.so for the active Android ABI(s). Requires voca + rust + NDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRATE="$ROOT/app/src/main/rust/hanami_dizione"
OUT="$ROOT/app/build/rust-jni"
# voca sources are pinned in vendor/VOCA_COMMIT; CI fetches before Gradle as well.
if [[ ! -d "$ROOT/vendor/voca/crates/dizione" ]]; then
  bash "$ROOT/scripts/fetch-voca.sh"
fi
if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo not installed; skipping hanami_dizione JNI"
  exit 0
fi
if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  fi
fi
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  echo "ANDROID_NDK_HOME not set; skipping hanami_dizione JNI (router falls back to system TTS)"
  exit 0
fi
export ANDROID_NDK_HOME="$NDK"
TARGETS=()
ABIS=()
if [[ "${HANAMI_BUILD_ABI:-arm64-v8a}" == *x86_64* ]] || [[ "${HANAMI_BUILD_ALL_ABIS:-}" == "1" ]]; then
  TARGETS+=(x86_64-linux-android)
  ABIS+=(x86_64)
fi
if [[ "${HANAMI_BUILD_ABI:-arm64-v8a}" == *arm64* ]] || [[ "${HANAMI_BUILD_ALL_ABIS:-}" == "1" ]]; then
  TARGETS+=(aarch64-linux-android)
  ABIS+=(arm64-v8a)
fi
HOST_PREBUILT="$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 1 -mindepth 1 -type d | head -1)"
clang21() {
  case "$1" in
    arm64-v8a) echo "$HOST_PREBUILT/bin/aarch64-linux-android21-clang" ;;
    x86_64) echo "$HOST_PREBUILT/bin/x86_64-linux-android21-clang" ;;
  esac
}
ar_tool() { echo "$HOST_PREBUILT/bin/llvm-ar"; }
for i in "${!TARGETS[@]}"; do
  target="${TARGETS[$i]}"
  abi="${ABIS[$i]}"
  rustup target add "$target" >/dev/null 2>&1 || true
  dest="$OUT/$abi"
  mkdir -p "$dest"
  linker="$(clang21 "$abi")"
  if [[ ! -x "$linker" ]]; then
    echo "missing NDK linker for $abi; skipping hanami_dizione"
    continue
  fi
  case "$target" in
    aarch64-linux-android)
      export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
      export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$("$ar_tool")"
      ;;
    x86_64-linux-android)
      export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$linker"
      export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="$("$ar_tool")"
      ;;
  esac
  if command -v cargo-ndk >/dev/null 2>&1; then
    (cd "$CRATE" && cargo ndk -t "$abi" -o "$dest" build --release) || continue
  else
    (cd "$CRATE" && cargo build --release --target "$target") || continue
    cp "$CRATE/target/$target/release/libhanami_dizione.so" "$dest/"
  fi
  echo "built $dest/libhanami_dizione.so"
done