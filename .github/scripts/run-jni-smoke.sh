#!/usr/bin/env bash
# Run on the hosted Android emulator. If the native test fails, its nonzero exit
# status must fail the workflow; logcat and Android test XML remain available.
set -Eeuo pipefail

mkdir -p app/build/ci-artifacts

collect_diagnostics() {
  local exit_code="${1:-0}"
  adb devices -l > app/build/ci-artifacts/adb-devices.txt 2>&1 || true
  adb logcat -d -v threadtime > app/build/ci-artifacts/emulator-logcat.txt 2>&1 || true
  if [ "$exit_code" -ne 0 ]; then
    # Best-effort screenshot of the last emulator state for failed UI assertions.
    adb exec-out screencap -p > app/build/ci-artifacts/failure-screen.png 2>/dev/null || true
    adb shell dumpsys activity activities > app/build/ci-artifacts/activity-dump.txt 2>&1 || true
  fi
}
trap 'collect_diagnostics "$?"' EXIT

adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"
adb logcat -c || true

# The Gradle connected test installs both APKs, launches instrumentation and
# returns failure on failed assertions. Unlike assembleSmokeDebugAndroidTest,
# it actually exercises the JNI model load/generate/stop/retry path.
timeout 30m gradle :app:connectedSmokeDebugAndroidTest --no-daemon --stacktrace

# Verify DataStore durability across an actual force-stop/new process.
bash .github/scripts/verify-settings-restart.sh

# Verify completed chat records survive an actual force-stop/new process.
bash .github/scripts/verify-history-restart.sh

# Verify speech/tool records are visible after restart and can be deleted end-to-end.
bash .github/scripts/verify-history-journey.sh
