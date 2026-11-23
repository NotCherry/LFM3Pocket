#!/usr/bin/env bash
# Run as android-emulator-runner pre-emulator-launch-script to stabilize adb before boot.
set -Eeuo pipefail

attempt="${1:-1}"
echo "=== Pre-emulator launch (infrastructure attempt ${attempt}/2): reset adb ==="
adb kill-server
adb start-server
adb devices -l