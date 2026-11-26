# Local diagnostics (R02)

Hanami records **on-device-only** timing breadcrumbs in app-private storage. Nothing is uploaded.

## What is recorded

| Kind | When | Notes |
| --- | --- | --- |
| `MODEL_LOAD` | After each GGUF load attempt | Duration ms + ok/error detail |
| `FIRST_TOKEN` | When instrumented | Optional; used by operators / future hooks |
| `TURN_COMPLETE` | Stress / operator notes | Stop or completion markers |
| `SPEECH` | Spoken-response timing | Optional PCM size notes |
| `MEMORY` | `Application.onTrimMemory` | Used heap MB + trim level |
| `STRESS` | Unload/reload loops | Emulator stress E2E + manual runs |

File: `files/diagnostics.ndjson` (newline-delimited JSON). Pull with:

```bash
adb shell run-as dev.notcherry.hanami cat files/diagnostics.ndjson
```

## Crash log (local only)

Decision: [ADR 0004](adr/0004-local-crash-log.md). Hanami also keeps `files/crash-log.txt`:

- Last uncaught Java exception (thread name + stack)
- On Android 11+, recent `ApplicationExitInfo` reasons (including native abort / low memory / ANR, and Android 17 `MemoryLimiter:AnonSwap` annotations per [ADR 0026](adr/0026-android-12-to-17-compatibility-and-permission-gates.md))

Open **Settings → Crash log** to view, copy, export/share as text, start the opt-in token-gated Wi-Fi log viewer, or clear. Nothing is uploaded automatically.

## GitHub issues & opt-in paste uploads

Local log export/copy is separated from lightweight, multi-purpose GitHub issue creation (ideas/features, device-info-only bug reports, device compatibility reports, blank issues, and de-duplicated paste-backed bug reports); see [ADR 0028](adr/0028-separate-diagnostics-export-from-github-issues.md) and [`docs/plans/diagnostics-and-github-issues-plan.md`](plans/diagnostics-and-github-issues-plan.md).

## Privacy contract

- `LocalDiagnostics.uploadsTelemetry()` is always `false`.
- Crash log export and copy are on-device only; optional paste uploads (`dpaste.org` / `paste.rs`) run only after explicit user confirmation of the sanitized preview.
- No analytics SDKs or background telemetry sockets are used by this recorder.
- Operators must redact paths/logs before sharing ARM64 evidence.

## ARM64 benchmark report

Physical-device timing still follows `docs/ARM64_OMNI_TEST_PROTOCOL.md` (RD-02 first-token latency, RD-03 stop latency, RD-07 speech). After a device run, attach:

1. Device model + Android version
2. GGUF / projector SHA256
3. Pulled `diagnostics.ndjson`
4. Stopwatch or probe JSON for Omni quality cases

Emulator CI proves the recorder + unload/reload/stop/background-mic stress recovery (`DiagnosticsStressE2eTest`), not Omni throughput.
