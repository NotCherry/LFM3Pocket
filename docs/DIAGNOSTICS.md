# Local diagnostics (R02)

LFM3 Pocket records **on-device-only** timing breadcrumbs in app-private storage. Nothing is uploaded.

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
adb shell run-as com.notcherry.lfm3pocket cat files/diagnostics.ndjson
```

## Privacy contract

- `LocalDiagnostics.uploadsTelemetry()` is always `false`.
- No sockets, HTTP clients, or analytics SDKs are used by this recorder.
- Operators must redact paths/logs before sharing ARM64 evidence.

## ARM64 benchmark report

Physical-device timing still follows `docs/ARM64_OMNI_TEST_PROTOCOL.md` (RD-02 first-token latency, RD-03 stop latency, RD-07 speech). After a device run, attach:

1. Device model + Android version
2. GGUF / projector SHA256
3. Pulled `diagnostics.ndjson`
4. Stopwatch or probe JSON for Omni quality cases

Emulator CI proves the recorder + unload/reload/stop/background-mic stress recovery (`DiagnosticsStressE2eTest`), not Omni throughput.
