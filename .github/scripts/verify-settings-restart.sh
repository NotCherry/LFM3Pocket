#!/usr/bin/env bash
# Run after connected tests; reinstall preserves local data, force-stop guarantees a new process.
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
  result="app/build/ci-artifacts/settings-restart-${phase}.txt"
  timeout 180s adb shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.InferenceSettingsE2eTest \
    -e settingsPhase "$phase" \
    "$HANAMI_INSTRUMENTATION_RUNNER" > "$result"
  cat "$result"
  python3 .github/scripts/assert-instrumentation-result.py "$result"

done