#!/usr/bin/env bash
# Run as android-emulator-runner pre-emulator-launch-script to stabilize adb before boot.
set -Eeuo pipefail

attempt="${1:-1}"
echo "=== Pre-emulator launch (infrastructure attempt ${attempt}/2): reset adb ==="
adb kill-server || true
adb start-server

if [ "$attempt" = "2" ]; then
  echo "Preparing attempt 2: stopping leftover emulators from attempt 1 (if any)"
  while read -r serial; do
    [ -z "$serial" ] && continue
    adb -s "$serial" emu kill 2>/dev/null || true
  done < <(adb devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }')
  adb kill-server || true
  adb start-server
fi

adb devices -l