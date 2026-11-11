#!/usr/bin/env bash
# Prove the full saved text/voice/tool transcript remains inspectable after a
# real process stop, then exercise fresh-session isolation and transcript deletion.
set -Eeuo pipefail
ADB=(adb -s emulator-5554)
mkdir -p app/build/ci-artifacts
"${ADB[@]}" install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
"${ADB[@]}" install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
for phase in prepare verify; do
  "${ADB[@]}" shell am force-stop com.notcherry.lfm3pocket
  "${ADB[@]}" shell am force-stop com.notcherry.lfm3pocket.test
  result="app/build/ci-artifacts/history-journey-${phase}.txt"
  timeout 120s "${ADB[@]}" shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.HistoryJourneyE2eTest \
    -e h05Phase "$phase" \
    com.notcherry.lfm3pocket.test/com.notcherry.lfm3pocket.UiE2eTestRunner > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"
done
