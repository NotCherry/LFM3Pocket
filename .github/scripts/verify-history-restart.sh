#!/usr/bin/env bash
# Exercise a completed/cancelled chat, then verify its Room records in a fresh
# instrumentation process after Android force-stops the app between phases.
set -Eeuo pipefail
_ci_scripts="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../scripts/lib/app-ids.sh
source "${_ci_scripts}/../../scripts/lib/app-ids.sh"
mkdir -p app/build/ci-artifacts
if [ "${DURABILITY_SKIP_INSTALL:-0}" != "1" ]; then
  adb install -r app/build/outputs/apk/smoke/debug/app-smoke-debug.apk
  adb install -r app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk
fi
for phase in prepare verify; do
  adb shell am force-stop "$HANAMI_APP_ID"
  adb shell am force-stop "$HANAMI_TEST_PKG"
  result="app/build/ci-artifacts/history-restart-${phase}.txt"
  timeout 120s adb shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.HistoryPersistenceE2eTest \
    -e historyPhase "$phase" \
    "$HANAMI_INSTRUMENTATION_RUNNER" > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"
done