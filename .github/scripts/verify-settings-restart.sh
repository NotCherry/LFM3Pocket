#!/usr/bin/env bash
# Run after connected tests; reinstall preserves local data, force-stop guarantees a new process.
set -Eeuo pipefail
mkdir -p app/build/ci-artifacts
adb install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
adb install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
for phase in prepare verify; do
  adb shell am force-stop com.notcherry.lfm3pocket
  adb shell am force-stop com.notcherry.lfm3pocket.test
  result="app/build/ci-artifacts/settings-restart-${phase}.txt"
  timeout 120s adb shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.InferenceSettingsE2eTest \
    -e settingsPhase "$phase" \
    com.notcherry.lfm3pocket.test/com.notcherry.lfm3pocket.UiE2eTestRunner > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"

done
