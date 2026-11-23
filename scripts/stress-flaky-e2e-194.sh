#!/usr/bin/env bash
set -euo pipefail

# Targeted stress run for issue #194: repeat the two historically flaky
# instrumentation tests on a connected device/emulator until one fails.
#
# Usage:
#   bash scripts/stress-flaky-e2e-194.sh [runs]
#
# Env:
#   STRESS_RUNS           Override run count (default: arg1 or 10)
#   STRESS_ARTIFACT_DIR   Directory for per-failure diagnostics and summary
#   PKG                   Application package (default: com.notcherry.lfm3pocket)
#   RUNNER                am instrument target (default:
#                         dev.notcherry.hanami.test/com.notcherry.lfm3pocket.UiE2eTestRunner)
#
# Requires: adb-connected device/emulator and smoke debug + androidTest APKs
# installed. On failure, captures instrument output, logcat tail, active
# notifications, runtime service state, and window/activity dumps when
# STRESS_ARTIFACT_DIR is set.

RUNS="${STRESS_RUNS:-${1:-10}}"
PKG="${PKG:-com.notcherry.lfm3pocket}"
RUNNER="${RUNNER:-dev.notcherry.hanami.test/com.notcherry.lfm3pocket.UiE2eTestRunner}"
ARTIFACT_DIR="${STRESS_ARTIFACT_DIR:-}"
TESTS=(
  "com.notcherry.lfm3pocket.BackgroundNotificationE2eTest#loadedModelAndActiveReplyDriveTheOngoingNotification"
  "com.notcherry.lfm3pocket.ChatNewChatE2eTest#openChatResumesThenTopBarNewChatStartsEmptySession"
)

mkdir -p "${ARTIFACT_DIR}" 2>/dev/null || true

capture_failure() {
  local run="$1" test="$2" out="$3"
  local slug
  slug="$(printf '%s' "$test" | tr '#./:' '____' )"
  echo "FAILED on run ${run}/${RUNS}: ${test}" >&2
  if [[ -z "$ARTIFACT_DIR" ]]; then
    return 0
  fi
  local dir="${ARTIFACT_DIR}/run-${run}-${slug}"
  mkdir -p "$dir"
  printf '%s\n' "$out" > "${dir}/instrument.txt"
  adb logcat -d -t 4000 > "${dir}/logcat.txt" 2>&1 || true
  adb shell dumpsys notification --noredact > "${dir}/dumpsys-notification.txt" 2>&1 || true
  adb shell dumpsys activity services "$PKG" > "${dir}/dumpsys-services.txt" 2>&1 || true
  adb shell dumpsys activity activities > "${dir}/dumpsys-activities.txt" 2>&1 || true
  adb shell dumpsys window windows > "${dir}/dumpsys-window.txt" 2>&1 || true
  adb exec-out screencap -p > "${dir}/screen.png" 2>/dev/null || true
  cat > "${dir}/summary.txt" <<EOF
issue: 194
run: ${run}/${RUNS}
test: ${test}
package: ${PKG}
runner: ${RUNNER}
utc: $(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)
EOF
  echo "Diagnostics captured in ${dir}" >&2
}

passed=0
echo "Instrumentation target: ${RUNNER} (package ${PKG})"
for i in $(seq 1 "$RUNS"); do
  echo "== stress run $i/$RUNS =="
  for test in "${TESTS[@]}"; do
    echo "-- $test"
    out="$(adb shell am instrument -w -r -e class "$test" "$RUNNER" 2>&1 || true)"
    printf '%s\n' "$out"
    if printf '%s\n' "$out" | grep -qE 'FAILURES!!!|Error in '; then
      capture_failure "$i" "$test" "$out"
      exit 1
    fi
    if ! printf '%s\n' "$out" | grep -q 'OK ('; then
      capture_failure "$i" "$test" "$out"
      echo "Could not confirm success for $test on run $i" >&2
      exit 1
    fi
    passed=$((passed + 1))
  done
done

summary="All ${RUNS} runs passed for ${#TESTS[@]} targeted #194 tests (${passed} instrumentations)."
echo "$summary"
if [[ -n "$ARTIFACT_DIR" ]]; then
  mkdir -p "$ARTIFACT_DIR"
  cat > "${ARTIFACT_DIR}/stress-summary.txt" <<EOF
issue: 194
result: PASS
runs: ${RUNS}
tests: ${#TESTS[@]}
instrumentations: ${passed}
utc: $(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)
EOF
  printf '%s\n' "$summary" >> "${ARTIFACT_DIR}/stress-summary.txt"
fi
