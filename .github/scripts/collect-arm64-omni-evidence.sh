#!/usr/bin/env bash
# Host-side, read-only evidence capture for a PHYSICAL ARM64 Android phone.
# A successful collection is NOT a passing multimodal model evaluation.
set -Eeuo pipefail

error() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'HELP'
Usage: collect-arm64-omni-evidence.sh --model MODEL.gguf --projector MMPROJ.gguf --apk APP.apk --out DIR
       [--serial ADB_SERIAL] [--package APP_ID] [--phase baseline|post] [--logcat]
       collect-arm64-omni-evidence.sh --self-test

Requires adb, sha256sum, a *physical* ARM64 Android device and installed
com.notcherry.lfm3pocket (or --package APP_ID). Does not install files, upload data, screenshot,
record audio, or mark hardware validation PASS.
HELP
}
validate_gguf() {
  local file="$1"
  [[ -f "$file" && -s "$file" ]] || return 1
  [[ "$(LC_ALL=C head -c 4 -- "$file")" == GGUF ]]
}
self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap "rm -rf -- '$tmp'" EXIT
  printf 'GGUFtest' > "$tmp/good.gguf"
  printf 'BAD!test' > "$tmp/bad.gguf"
  validate_gguf "$tmp/good.gguf" || error 'valid GGUF signature rejected'
  if validate_gguf "$tmp/bad.gguf"; then error 'invalid GGUF signature accepted'; fi
  if validate_gguf "$tmp/missing.gguf"; then error 'nonexistent GGUF accepted'; fi
  printf 'Signature validator self-test passed (not a real model test).\n'
}
if [[ "${1:-}" == --self-test ]]; then self_test; exit 0; fi

model=''
projector=''
apk=''
output=''
serial=''
package='com.notcherry.lfm3pocket'
phase='baseline'
capture_logcat=0
while (($#)); do
  case "$1" in
    --model|--projector|--apk|--out|--serial|--phase|--package)
      (($#>=2)) || error "missing argument for $1"
      case "$1" in
        --model) model="$2";;
        --projector) projector="$2";;
        --apk) apk="$2";;
        --out) output="$2";;
        --serial) serial="$2";;
        --phase) phase="$2";;
        --package) package="$2";;
      esac
      shift 2;;
    --logcat) capture_logcat=1; shift;;
    -h|--help) usage; exit 0;;
    *) usage >&2; error "unrecognized flag $1";;
  esac
done
[[ -n "$model" && -n "$projector" && -n "$apk" && -n "$output" ]] ||
  { usage >&2; error 'all --model, --projector, --apk and --out are required'; }
[[ "$phase" == baseline || "$phase" == post ]] || error 'phase must be baseline or post'
validate_gguf "$model" || error "invalid/missing GGUF model file: $model"
validate_gguf "$projector" || error "invalid/missing GGUF projector file: $projector"
[[ -s "$apk" ]] || error "missing APK file: $apk"
command -v adb >/dev/null || error 'adb not found'
command -v sha256sum >/dev/null || error 'sha256sum not found'
[[ -z "$serial" || "$serial" =~ ^[A-Za-z0-9._:-]+$ ]] || error 'unsafe adb serial'
[[ "$package" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || error 'unsafe app package'

adb_cmd=(adb)
[[ -z "$serial" ]] || adb_cmd+=(-s "$serial")
prop() { "${adb_cmd[@]}" shell getprop "$1" | tr -d '\r'; }
current_serial="$("${adb_cmd[@]}" get-serialno)" || error 'unable to select adb device'
[[ -n "$current_serial" && "$current_serial" != unknown ]] || error 'missing adb device'
[[ "$(prop ro.kernel.qemu)" != 1 ]] || error 'emulator detected; requires real ARM64 phone'
abi="$(prop ro.product.cpu.abilist)"
[[ ",$abi," == *",arm64-v8a,"* ]] || error "device lacks arm64-v8a ABI: $abi"
"${adb_cmd[@]}" shell pm path "$package" | grep -q '^package:' ||
  error "$package is not installed on this device"
mkdir -p -- "$output"
timestamp="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
snapshot="$output/${phase}-device.txt"
[[ ! -e "$snapshot" ]] || error "existing snapshot would be overwritten: $snapshot"

model_sha="$(sha256sum -- "$model" | cut -d' ' -f1)"
proj_sha="$(sha256sum -- "$projector" | cut -d' ' -f1)"
apk_sha="$(sha256sum -- "$apk" | cut -d' ' -f1)"
{
  printf '# LFM3 Omni physical-device evidence (%s)\n\n' "$phase"
  printf 'Collection timestamp UTC: %s\n\n' "$timestamp"
  printf 'ADB serial: %s\n' "$current_serial"
  printf 'Device: %s %s\n' "$(prop ro.product.manufacturer)" "$(prop ro.product.model)"
  printf 'Build fingerprint: %s\n' "$(prop ro.build.fingerprint)"
  printf 'Android version: %s (API %s)\n' "$(prop ro.build.version.release)" "$(prop ro.build.version.sdk)"
  printf 'Supported ABI list: %s\n' "$abi"
  printf 'App package: %s\n' "$package"
  printf 'Model file: %s\n' "$(basename -- "$model")"
  printf 'Model SHA256: %s\n' "$model_sha"
  printf 'Projector file: %s\n' "$(basename -- "$projector")"
  printf 'Projector SHA256: %s\n' "$proj_sha"
  printf 'APK file: %s\n' "$(basename -- "$apk")"
  printf 'APK SHA256: %s\n' "$apk_sha"
  printf '\nThese hashes are captured locally, NOT compared with an official publisher manifest.\n'
  printf 'Model/projector release revision and license must be verified manually.\n'
  printf 'Capturing diagnostics alone is NOT a test PASS.\n'
} > "$snapshot"

"${adb_cmd[@]}" shell dumpsys package "$package" > "$output/${phase}-package.txt"
"${adb_cmd[@]}" shell dumpsys meminfo "$package" > "$output/${phase}-app-memory.txt"
"${adb_cmd[@]}" shell cat /proc/meminfo > "$output/${phase}-system-memory.txt"
"${adb_cmd[@]}" shell dumpsys battery > "$output/${phase}-battery.txt"
"${adb_cmd[@]}" shell dumpsys thermalservice > "$output/${phase}-thermal.txt" 2>&1 || true
if ((capture_logcat)); then
  "${adb_cmd[@]}" logcat -d -v threadtime > "$output/${phase}-logcat-sensitive.txt"
  printf 'WARNING: raw logcat may contain sensitive data; redact before sharing.\n' >&2
fi

if [[ ! -e "$output/manual-results.md" ]]; then
  {
    printf '# Real ARM64 Omni manual results (NOT AUTOMATICALLY VERIFIED)\n\n'
    printf 'Model revision / source / license: NOT_RECORDED\n'
    printf 'Projector matching release evidence: NOT_RECORDED\n'
    printf 'Test operator, date, app source commit: NOT_RECORDED\n\n'
    printf '| Case | Status | Observed response / metric | Evidence path | Issue / reason |\n'
    printf '| --- | --- | --- | --- | --- |\n'
    for i in $(seq -w 1 12); do
      printf '| RD-%s | NOT_RUN | — | — | — |\n' "$i"
    done
  } > "$output/manual-results.md"
fi
printf 'Evidence saved to %s. No real-device test has been marked PASS.\n' "$output"
