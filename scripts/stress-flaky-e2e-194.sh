#!/usr/bin/env bash
set -euo pipefail

# Targeted stress run for issue #194: repeat the two historically flaky
# instrumentation tests on a connected device/emulator until one fails.
#
# Usage:
#   bash scripts/stress-flaky-e2e-194.sh [runs]
#
# Requires: adb-connected device/emulator and a built smoke debug + androidTest APK.
# Example:
#   gradle :app:assembleSmokeDebug :app:assembleSmokeDebugAndroidTest
#   adb install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
#   adb install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
#   bash scripts/stress-flaky-e2e-194.sh 10

RUNS="${1:-10}"
PKG="${PKG:-com.notcherry.lfm3pocket}"
RUNNER="${PKG}.test/androidx.test.runner.AndroidJUnitRunner"
TESTS=(
  "com.notcherry.lfm3pocket.BackgroundNotificationE2eTest#loadedModelAndActiveReplyDriveTheOngoingNotification"
  "com.notcherry.lfm3pocket.ChatNewChatE2eTest#openChatResumesThenTopBarNewChatStartsEmptySession"
)

for i in $(seq 1 "$RUNS"); do
  echo "== stress run $i/$RUNS =="
  for test in "${TESTS[@]}"; do
    echo "-- $test"
    out="$(adb shell am instrument -w -r -e class "$test" "$RUNNER" 2>&1 || true)"
    printf '%s\n' "$out"
    if printf '%s\n' "$out" | grep -qE 'FAILURES!!!|Error in '; then
      echo "FAILED on run $i: $test" >&2
      exit 1
    fi
    if ! printf '%s\n' "$out" | grep -q 'OK ('; then
      echo "Could not confirm success for $test on run $i" >&2
      exit 1
    fi
  done
done

echo "All $RUNS runs passed for: ${TESTS[*]}"
