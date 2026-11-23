#!/usr/bin/env bash
# After a failed emulator E2E step, allow at most one full emulator re-start only when
# tests never started (pre-test infrastructure flake). Never mask real test failures.
set -Eeuo pipefail

MARKER="app/build/ci-artifacts/e2e-tests-started"
RESULTS_DIR="app/build/outputs/androidTest-results/connected"

if [ -f "$MARKER" ]; then
  echo "E2E tests started on attempt 1 ($MARKER present); not retrying emulator infrastructure."
  echo "retry=false" >> "${GITHUB_OUTPUT}"
  exit 0
fi

if [ -d "$RESULTS_DIR" ] && find "$RESULTS_DIR" -name 'TEST-*.xml' -print -quit | grep -q .; then
  echo "JUnit connected results present; not retrying emulator infrastructure."
  echo "retry=false" >> "${GITHUB_OUTPUT}"
  exit 0
fi

echo "No test start marker or connected JUnit XML; treating failure as pre-test infrastructure."
echo "retry=true" >> "${GITHUB_OUTPUT}"