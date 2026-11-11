#!/usr/bin/env bash
# Exercise a completed/cancelled chat, then verify its Room records in a fresh
# instrumentation process after Android force-stops the app between phases.
set -Eeuo pipefail
mkdir -p app/build/ci-artifacts
adb install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
adb install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
for phase in prepare verify; do
  adb shell am force-stop com.notcherry.lfm3pocket
  adb shell am force-stop com.notcherry.lfm3pocket.test
  result="app/build/ci-artifacts/history-restart-${phase}.txt"
  timeout 120s adb shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.HistoryPersistenceE2eTest \
    -e historyPhase "$phase" \
    com.notcherry.lfm3pocket.test/com.notcherry.lfm3pocket.UiE2eTestRunner > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"
done
