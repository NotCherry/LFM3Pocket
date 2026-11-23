#!/usr/bin/env bash
# Run on the hosted Android emulator. If a test fails, its nonzero exit status
# must fail the workflow; logcat and Android test XML remain available.
#
# Tier contracts (arg1 or E2E_TIER, default: deep). Connected class sets are
# sourced from .github/scripts/assert-android-test-results.py so executed tests
# and gate expectations stay explicit and aligned.
#
#   smoke - fast PR-required tier:
#           * connected: GenerationSmokeTest (real JNI load/generate/stream/
#             stop/retry emulator proof) + ComposeHostE2eTest (app boot/Compose
#             host and core chat UI flow on fake engine)
#           * scripted cross-process persistence proof: verify-settings-restart.sh
#   deep  - broad/nightly tier:
#           * connected: full explicit androidTest class contract, including
#             moved heavy/matrix/stress/native-deep coverage (e.g.
#             GenerationDeepSmokeTest, TextReadableAtScaleE2eTest,
#             DiagnosticsStressE2eTest) and all other intended test classes
#           * scripted cross-process durability proofs: settings, history-restart,
#             history-journey
set -Eeuo pipefail

MODE="${1:-${E2E_TIER:-deep}}"
case "$MODE" in
  smoke|deep) ;;
  *)
    echo "ERROR: unknown E2E tier '$MODE' (expected smoke or deep)" >&2
    exit 2
    ;;
esac

mkdir -p app/build/ci-artifacts
echo "E2E tier: $MODE" | tee app/build/ci-artifacts/e2e-tier.txt

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

# When APKs are already assembled (CI pre-assemble step), install them directly
# and skip redundant assemble tasks to avoid rebuild/configuration work.
SMOKE_APP_APK="app/build/outputs/apk/smoke/debug/app-smoke-debug.apk"
SMOKE_TEST_APK="app/build/outputs/apk/androidTest/smoke/debug/app-smoke-debug-androidTest.apk"
SMOKE_APKS_PREBUILT=false
if [[ -f "$SMOKE_APP_APK" && -f "$SMOKE_TEST_APK" ]]; then
  SMOKE_APKS_PREBUILT=true
  adb install -r "$SMOKE_APP_APK"
  adb install -r "$SMOKE_TEST_APK"
fi

TIER_CLASSES="$(python3 .github/scripts/assert-android-test-results.py --mode "$MODE" --list-classes)"
if [ -z "$TIER_CLASSES" ]; then
  echo "ERROR: empty class list for tier '$MODE'" >&2
  exit 2
fi

run_connected() {
  local timeout_s="$1"
  if [ "$SMOKE_APKS_PREBUILT" = "true" ]; then
    timeout "${timeout_s}" gradle :app:connectedSmokeDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$TIER_CLASSES" \
      -x :app:assembleSmokeDebug -x :app:assembleSmokeDebugAndroidTest \
      --no-daemon --stacktrace
  else
    timeout "${timeout_s}" gradle :app:connectedSmokeDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$TIER_CLASSES" \
      --no-daemon --stacktrace
  fi
}

if [ "$MODE" = "smoke" ]; then
  run_connected 30m

  # Install once for post-run durability proof; reinstall preserves local data.
  adb install -r "$SMOKE_APP_APK"
  adb install -r "$SMOKE_TEST_APK"
  export DURABILITY_SKIP_INSTALL=1

  # Cross-process persistence proof (DataStore across force-stop/new process).
  bash .github/scripts/verify-settings-restart.sh
else
  run_connected 45m

  # Install once for post-run durability proofs; reinstall preserves local data.
  adb install -r "$SMOKE_APP_APK"
  adb install -r "$SMOKE_TEST_APK"
  export DURABILITY_SKIP_INSTALL=1

  # Cross-process persistence proofs.
  bash .github/scripts/verify-settings-restart.sh
  bash .github/scripts/verify-history-restart.sh
  bash .github/scripts/verify-history-journey.sh

  # Issue #194 acceptance evidence: repeat the historically flaky notification and
  # conversation-resume tests on the same clean emulator run to demonstrate stability.
  # PKG is the installed application id (same as verify-*.sh); the stress script
  # targets dev.notcherry.hanami.test/com.notcherry.lfm3pocket.UiE2eTestRunner.
  PKG=dev.notcherry.hanami \
  STRESS_RUNS="${E2E_194_STRESS_RUNS:-5}" \
  STRESS_ARTIFACT_DIR="app/build/ci-artifacts/stress-194" \
    bash scripts/stress-flaky-e2e-194.sh
fi
