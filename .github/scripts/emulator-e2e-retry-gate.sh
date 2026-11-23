#!/usr/bin/env bash
# After a failed emulator E2E step, allow at most one full emulator re-start only when
# tests never started (pre-test infrastructure flake). Never mask real test failures.
set -Eeuo pipefail

MARKER="app/build/ci-artifacts/e2e-tests-started"
RESULTS_DIR="app/build/outputs/androidTest-results/connected"
ARCHIVE_DIR="app/build/ci-artifacts/attempt-1"

archive_attempt1_artifacts() {
  mkdir -p "$ARCHIVE_DIR"
  if [ -d app/build/ci-artifacts ]; then
    find app/build/ci-artifacts -mindepth 1 -maxdepth 1 ! -name attempt-1 -print0 |
      xargs -0 -r -I{} mv "{}" "$ARCHIVE_DIR/" || true
  fi
  if [ -d "$RESULTS_DIR" ]; then
    mkdir -p "$ARCHIVE_DIR/androidTest-results"
    mv "$RESULTS_DIR" "$ARCHIVE_DIR/androidTest-results/" 2>/dev/null || true
  fi
  echo "Archived attempt 1 diagnostics under ${ARCHIVE_DIR}/"
}

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

archive_attempt1_artifacts
echo "No test start marker or connected JUnit XML; treating failure as pre-test infrastructure."
echo "retry=true" >> "${GITHUB_OUTPUT}"