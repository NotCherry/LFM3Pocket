#!/usr/bin/env bash
# Wait until the emulator adb endpoint is usable, fully booted, and core system
# services are published. Handles transient "adb: device offline" during CI boot.
set -Eeuo pipefail

TIMEOUT_S="${EMULATOR_READY_TIMEOUT_S:-120}"
POLL_S="${EMULATOR_READY_POLL_S:-2}"
ATTEMPT_TAG="${EMULATOR_INFRA_ATTEMPT:-}"
TARGET_SERIAL="${ANDROID_SERIAL:-}"

if [ -n "$ATTEMPT_TAG" ]; then
  echo "=== Emulator readiness (infrastructure attempt ${ATTEMPT_TAG}/2, timeout ${TIMEOUT_S}s) ==="
else
  echo "=== Emulator readiness (timeout ${TIMEOUT_S}s) ==="
fi

remaining_s() {
  local left=$((deadline - SECONDS))
  if (( left < 1 )); then
    echo 1
  else
    echo "$left"
  fi
}

adb_wait_bounded() {
  local serial="${1:-}"
  local left
  left="$(remaining_s)"
  if [ -n "$serial" ]; then
    timeout "${left}s" adb -s "$serial" wait-for-device || true
  else
    timeout "${left}s" adb wait-for-device || true
  fi
}

adb_pick_serial() {
  local lines
  lines="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  if [ -z "$lines" ]; then
    return 1
  fi
  local count
  count="$(printf '%s\n' "$lines" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ "$count" -eq 1 ]; then
    printf '%s\n' "$lines" | head -n 1
    return 0
  fi
  if [ -n "$TARGET_SERIAL" ]; then
    if printf '%s\n' "$lines" | grep -qx "$TARGET_SERIAL"; then
      echo "$TARGET_SERIAL"
      return 0
    fi
  fi
  local preferred
  preferred="$(printf '%s\n' "$lines" | awk '/^emulator-/{ print; exit }')"
  if [ -n "$preferred" ]; then
    echo "$preferred"
    return 0
  fi
  printf '%s\n' "$lines" | head -n 1
}

adb_recover_connection() {
  local reason="$1"
  local serial="${2:-}"
  echo "adb recover: ${reason} (reconnect + restart adb server)"
  if [ -n "$serial" ]; then
    adb -s "$serial" reconnect offline 2>/dev/null || true
    adb -s "$serial" reconnect 2>/dev/null || true
  else
    adb reconnect offline 2>/dev/null || true
    adb reconnect 2>/dev/null || true
  fi
  adb kill-server || true
  adb start-server
  adb_wait_bounded "$serial"
}

service_ready() {
  local serial="$1"
  local name="$2"
  if adb -s "$serial" shell "service check ${name}" 2>/dev/null | grep -q 'Service '"${name}"': found'; then
    return 0
  fi
  return 1
}

boot_completed() {
  local serial="$1"
  local value
  value="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [ "$value" = "1" ]
}

deadline=$((SECONDS + TIMEOUT_S))
last_log=0
serial=""

while (( SECONDS < deadline )); do
  devices_line="$(adb devices 2>&1 || true)"
  if echo "$devices_line" | grep -q 'offline'; then
    adb_recover_connection "device listed as offline" "$serial"
    sleep "$POLL_S"
    continue
  fi

  if ! serial="$(adb_pick_serial)"; then
    serial=""
    if (( SECONDS - last_log >= 15 )); then
      echo "waiting for emulator device (adb wait-for-device, bounded)…"
      last_log=$SECONDS
    fi
    adb_wait_bounded ""
    sleep "$POLL_S"
    continue
  fi

  state="$(adb -s "$serial" get-state 2>/dev/null || echo unknown)"
  if [ "$state" != "device" ]; then
    adb_recover_connection "get-state=${state}" "$serial"
    sleep "$POLL_S"
    continue
  fi

  if ! boot_completed "$serial"; then
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: sys.boot_completed not yet 1…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  missing=()
  for svc in input package activity window; do
    if ! service_ready "$serial" "$svc"; then
      missing+=("$svc")
    fi
  done

  if [ "${#missing[@]}" -ne 0 ]; then
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: waiting for services: ${missing[*]}…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  if ! adb -s "$serial" shell cmd input help >/dev/null 2>&1; then
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: cmd input not ready yet…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  export ANDROID_SERIAL="$serial"
  echo "device ${serial}: boot_complete + services (input, package, activity, window) ready"
  echo "=== Emulator ready (ANDROID_SERIAL=${serial}) ==="
  adb -s "$serial" shell getprop dev.bootcomplete 2>/dev/null | sed 's/^/dev.bootcomplete=/' || true
  exit 0
done

echo "ERROR: emulator not ready within ${TIMEOUT_S}s (offline adb, boot, or system services)" >&2
adb devices -l >&2 || true
exit 10