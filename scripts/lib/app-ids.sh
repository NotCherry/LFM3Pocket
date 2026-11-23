#!/usr/bin/env bash
# Shared Hanami smoke E2E application and instrumentation identifiers.
# Source from shell scripts; do not execute directly.

# Installed application id (smoke/debug APK).
HANAMI_APP_ID="${HANAMI_APP_ID:-dev.notcherry.hanami}"
HANAMI_TEST_PKG="${HANAMI_TEST_PKG:-${HANAMI_APP_ID}.test}"
HANAMI_INSTRUMENTATION_RUNNER="${HANAMI_INSTRUMENTATION_RUNNER:-${HANAMI_TEST_PKG}/com.notcherry.lfm3pocket.UiE2eTestRunner}"

# Legacy env overrides (scripts/stress-flaky-e2e-194.sh).
PKG="${PKG:-$HANAMI_APP_ID}"
RUNNER="${RUNNER:-$HANAMI_INSTRUMENTATION_RUNNER}"