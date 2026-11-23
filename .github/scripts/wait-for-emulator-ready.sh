#!/usr/bin/env bash
# Wait until the emulator adb endpoint is usable, fully booted, and core system
# services are published. Handles transient "adb: device offline" during CI boot.
set -Eeuo pipefail

TIMEOUT_S="${EMULATOR_READY_TIMEOUT_S:-180}"
POLL_S="${EMULATOR_READY_POLL_S:-2}"
ATTEMPT_TAG="${EMULATOR_INFRA_ATTEMPT:-}"

if [ -n "$ATTEMPT_TAG" ]; then
  echo "=== Emulator readiness (infrastructure attempt ${ATTEMPT_TAG}/2, timeout ${TIMEOUT_S}s) ==="
else
  echo "=== Emulator readiness (timeout ${TIMEOUT_S}s) ==="
fi

adb_device_serial() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

adb_recover_connection() {
  local reason="$1"
  echo "adb recover: ${reason} (reconnect + restart adb server)"
  adb reconnect offline 2>/dev/null || true
  adb reconnect 2>/dev/null || true
  adb kill-server
  adb start-server
  adb wait-for-device
}

service_ready() {
  local name="$1"
  if adb shell "service check ${name}" 2>/dev/null | grep -q 'Service '"${name}"': found'; then
    return 0
  fi
  return 1
}

boot_completed() {
  local value
  value="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [ "$value" = "1" ]
}

deadline=$((SECONDS + TIMEOUT_S))
last_log=0

while (( SECONDS < deadline )); do
  devices_line="$(adb devices 2>&1 || true)"
  if echo "$devices_line" | grep -q 'offline'; then
    adb_recover_connection "device listed as offline"
    sleep "$POLL_S"
    continue
  fi

  serial="$(adb_device_serial || true)"
  if [ -z "$serial" ]; then
    if (( SECONDS - last_log >= 15 )); then
      echo "waiting for emulator device (adb wait-for-device)…"
      last_log=$SECONDS
    fi
    adb wait-for-device
    sleep "$POLL_S"
    continue
  fi

  state="$(adb -s "$serial" get-state 2>/dev/null || echo unknown)"
  if [ "$state" != "device" ]; then
    adb_recover_connection "get-state=${state}"
    sleep "$POLL_S"
    continue
  fi

  if ! boot_completed; then
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: sys.boot_completed not yet 1…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  missing=()
  for svc in input package activity window; do
    if ! service_ready "$svc"; then
      missing+=("$svc")
    fi
  done

  if [ "${#missing[@]}" -eq 0 ]; then
    echo "device ${serial}: boot_complete + services (input, package, activity, window) ready"
  else
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: waiting for services: ${missing[*]}…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  # Extra sanity: `cmd input` should not throw ServiceNotFoundException once input is up.
  if ! adb shell cmd input help >/dev/null 2>&1; then
    if (( SECONDS - last_log >= 15 )); then
      echo "device ${serial}: cmd input not ready yet…"
      last_log=$SECONDS
    fi
    sleep "$POLL_S"
    continue
  fi

  echo "=== Emulator ready ==="
  adb -s "$serial" shell getprop dev.bootcomplete 2>/dev/null | sed 's/^/dev.bootcomplete=/' || true
  exit 0
done

echo "ERROR: emulator not ready within ${TIMEOUT_S}s (offline adb, boot, or system services)" >&2
adb devices -l >&2 || true
exit 10