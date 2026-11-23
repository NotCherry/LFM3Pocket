#!/usr/bin/env bash
set -euo pipefail

_stress_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/app-ids.sh
source "${_stress_dir}/lib/app-ids.sh"

# Targeted stress run for issue #194: repeat the two historically flaky
# instrumentation tests on a connected device/emulator until one fails.
#
# Usage:
#   bash scripts/stress-flaky-e2e-194.sh [runs]
#
# Env:
#   STRESS_RUNS                   Override run count (default: arg1 or 10)
#   STRESS_ARTIFACT_DIR           Directory for per-failure diagnostics and summary
#   STRESS_INSTRUMENT_TIMEOUT     Per-test am instrument timeout in seconds (default: 300)
#   STRESS_INSTRUMENT_KILL_AFTER  SIGKILL grace after timeout in seconds (default: 15)
#   PKG                           Application package (default: HANAMI_APP_ID)
#   RUNNER                        am instrument target (default: HANAMI_TEST_RUNNER)
#
# Requires: adb-connected device/emulator and smoke debug + androidTest APKs
# installed. On failure, captures instrument output, logcat tail, active
# notifications, runtime service state, and window/activity dumps when
# STRESS_ARTIFACT_DIR is set.

PKG="${PKG:-$HANAMI_APP_ID}"
RUNNER="${RUNNER:-$HANAMI_TEST_RUNNER}"
RUNS="${STRESS_RUNS:-${1:-10}}"
INSTRUMENT_TIMEOUT="${STRESS_INSTRUMENT_TIMEOUT:-300}"
INSTRUMENT_KILL_AFTER="${STRESS_INSTRUMENT_KILL_AFTER:-15}"
if [[ ! "$INSTRUMENT_TIMEOUT" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: STRESS_INSTRUMENT_TIMEOUT must be a positive integer (got: ${INSTRUMENT_TIMEOUT})" >&2
  exit 1
fi
if [[ ! "$INSTRUMENT_KILL_AFTER" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: STRESS_INSTRUMENT_KILL_AFTER must be a positive integer (got: ${INSTRUMENT_KILL_AFTER})" >&2
  exit 1
fi
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

kill_stuck_instrumentation() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb shell am force-stop "$HANAMI_TEST_PKG" >/dev/null 2>&1 || true
}

passed=0
echo "Instrumentation target: ${RUNNER} (package ${PKG})"
for i in $(seq 1 "$RUNS"); do
  echo "== stress run $i/$RUNS =="
  for test in "${TESTS[@]}"; do
    echo "-- $test"
    instrument_rc=0
    set +e
    out="$(timeout -k "${INSTRUMENT_KILL_AFTER}" "${INSTRUMENT_TIMEOUT}s" \
      adb shell am instrument -w -r -e class "$test" "$RUNNER" 2>&1)"
    instrument_rc=$?
    set -e
    if [[ "$instrument_rc" -eq 124 ]]; then
      out="${out}
ERROR: am instrument timed out after ${INSTRUMENT_TIMEOUT}s"
    elif [[ "$instrument_rc" -ne 0 ]]; then
      out="${out}
ERROR: am instrument exited ${instrument_rc}"
    fi
    printf '%s\n' "$out"
    if [[ "$instrument_rc" -ne 0 ]]; then
      capture_failure "$i" "$test" "$out"
      if [[ "$instrument_rc" -eq 124 ]]; then
        kill_stuck_instrumentation
      fi
      exit 1
    fi
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
