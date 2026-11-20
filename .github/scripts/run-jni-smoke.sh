#!/usr/bin/env bash
# Run on the hosted Android emulator. If a test fails, its nonzero exit status
# must fail the workflow; logcat and Android test XML remain available.
#
# Modes (arg1 or E2E_TIER, default: deep):
#   smoke - fast PR-required tier: minimal connected proof set including a real
#           JNI emulator proof, plus one cross-process persistence proof.
#   deep  - broad/nightly tier: full connected suite and all durability phases.
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

if [ "$MODE" = "smoke" ]; then
  # Minimal high-signal PR gate: real JNI load/generate/stop/retry proof and
  # core app/Compose host boot. Keep this set explicit and fast.
  SMOKE_CLASSES="com.notcherry.lfm3pocket.GenerationSmokeTest,com.notcherry.lfm3pocket.ComposeHostE2eTest"
  timeout 30m gradle :app:connectedSmokeDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$SMOKE_CLASSES" \
    --no-daemon --stacktrace

  # Cross-process persistence proof (DataStore across force-stop/new process).
  bash .github/scripts/verify-settings-restart.sh
else
  # Deep tier: full connected suite and all serial durability/journey proofs.
  timeout 45m gradle :app:connectedSmokeDebugAndroidTest --no-daemon --stacktrace

  # Verify DataStore durability across an actual force-stop/new process.
  bash .github/scripts/verify-settings-restart.sh

  # Verify completed chat records survive an actual force-stop/new process.
  bash .github/scripts/verify-history-restart.sh

  # Verify speech/tool records are visible after restart and can be deleted end-to-end.
  bash .github/scripts/verify-history-journey.sh
fi
