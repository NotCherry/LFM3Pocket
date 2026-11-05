#!/usr/bin/env bash
# Run on the hosted Android emulator. If the native test fails, its nonzero exit
# status must fail the workflow; logcat and Android test XML remain available.
set -Eeuo pipefail

mkdir -p app/build/ci-artifacts

collect_diagnostics() {
  adb devices -l > app/build/ci-artifacts/adb-devices.txt 2>&1 || true
  adb logcat -d -v threadtime > app/build/ci-artifacts/emulator-logcat.txt 2>&1 || true
}
trap collect_diagnostics EXIT

adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"
adb logcat -c || true

# The Gradle connected test installs both APKs, launches instrumentation and
# returns failure on failed assertions. Unlike assembleSmokeDebugAndroidTest,
# it actually exercises the JNI model load/generate/stop/retry path.
timeout 30m gradle :app:connectedSmokeDebugAndroidTest --no-daemon --stacktrace
