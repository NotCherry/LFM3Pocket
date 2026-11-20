#!/usr/bin/env bash
# Prove the full saved text/voice/tool transcript remains inspectable after a
# real process stop, then exercise fresh-session isolation and transcript deletion.
set -Eeuo pipefail
mkdir -p app/build/ci-artifacts
if [ "${DURABILITY_SKIP_INSTALL:-0}" != "1" ]; then
  adb install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
  adb install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
fi
for phase in prepare verify; do
  adb shell am force-stop dev.notcherry.hanami
  adb shell am force-stop dev.notcherry.hanami.test
  result="app/build/ci-artifacts/history-journey-${phase}.txt"
  timeout 120s adb shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.HistoryJourneyE2eTest \
    -e h05Phase "$phase" \
    dev.notcherry.hanami.test/com.notcherry.lfm3pocket.UiE2eTestRunner > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"
done
