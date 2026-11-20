#!/usr/bin/env bash
set -Eeuo pipefail

AVD_NAME="${AVD_NAME:-test}"
EMULATOR_PORT="${EMULATOR_PORT:-5554}"
SERIAL="emulator-${EMULATOR_PORT}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-600}"
EMULATOR_PID=""

# Ensure Android SDK tools are on PATH (platform-tools/adb, emulator).
export PATH="${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

cleanup() {
  local exit_code="${1:-0}"
  if [[ -n "${EMULATOR_PID}" ]] && kill -0 "${EMULATOR_PID}" 2>/dev/null; then
    adb -s "${SERIAL}" emu kill >/dev/null 2>&1 || true
    # Give the emulator time to shut down gracefully.
    sleep 5 || true
    kill "${EMULATOR_PID}" >/dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}
trap 'cleanup "$?"' EXIT

adb start-server >/dev/null 2>&1 || true

# Launch emulator in background (headless, from cached AVD snapshot when available).
"$ANDROID_HOME/emulator/emulator" \
  -avd "${AVD_NAME}" \
  -port "${EMULATOR_PORT}" \
  -no-snapshot-save \
  -no-window \
  -gpu swiftshader_indirect \
  -noaudio \
  -no-boot-anim \
  -no-metrics &
EMULATOR_PID=$!

# Wait for adb device and boot completion.
adb -s "${SERIAL}" wait-for-device
SECONDS_WAITED=0
until [[ "$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  if (( SECONDS_WAITED >= BOOT_TIMEOUT_SECONDS )); then
    echo "ERROR: emulator did not boot within ${BOOT_TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  sleep 2
  SECONDS_WAITED=$((SECONDS_WAITED + 2))
done

# Disable animations and unlock; input service may not be ready immediately after boot.
adb -s "${SERIAL}" shell settings put global window_animation_scale 0.0 >/dev/null 2>&1 || true
adb -s "${SERIAL}" shell settings put global transition_animation_scale 0.0 >/dev/null 2>&1 || true
adb -s "${SERIAL}" shell settings put global animator_duration_scale 0.0 >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  if adb -s "${SERIAL}" shell input keyevent 82 >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

export ANDROID_SERIAL="${SERIAL}"
bash .github/scripts/run-jni-smoke.sh
