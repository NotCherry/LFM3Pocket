#!/usr/bin/env bash
set -Eeuo pipefail

attempt1="${E2E_ATTEMPT_1_OUTCOME:?}"
retry="${E2E_INFRA_RETRY:-false}"
attempt2="${E2E_ATTEMPT_2_OUTCOME:-skipped}"

if [ "$attempt1" = "success" ]; then
  echo "Android emulator E2E passed on infrastructure attempt 1."
  exit 0
fi

if [ "$retry" != "true" ]; then
  echo "Android emulator E2E failed without infrastructure retry (tests may have run)."
  exit 1
fi

if [ "$attempt2" = "success" ]; then
  echo "Android emulator E2E passed on infrastructure attempt 2."
  exit 0
fi

echo "ERROR: Both emulator infrastructure attempts failed before a successful test run." >&2
exit 1