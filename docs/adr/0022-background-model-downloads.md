# ADR 0022 — Background model downloads (UIDT / WorkManager)

- Status: Accepted
- Date: 2026-11-23
- Related: [#261](https://github.com/NotCherry/LFM3Pocket/issues/261), [#271](https://github.com/NotCherry/LFM3Pocket/pull/271), [ADR 0021](0021-optional-cactus-whistle-stt.md), [ADR 0010](0010-tiny-voca-english-tts.md), [`CACTUS_WHISTLE_STT.md`](../CACTUS_WHISTLE_STT.md)

## Context

Pinned optional models (Whistle today; tiny-voca pack later) are large, user-initiated downloads. Running them on a raw `MainActivity` thread dies with the process, cannot show durable progress, and cannot cancel cleanly. [ADR 0021](0021-optional-cactus-whistle-stt.md) records *what* is downloaded and verified; this record covers *how* the transfer is scheduled and guarded.

## Decision

1. **Scheduler** — One shared `ModelDownloadManager` and `ModelDownloadCoordinator` (single-flight, SHA-256 before atomic install, discard only `.partial` on user cancel or hard failure).
2. **API 34+** — User-initiated `JobScheduler` work (`JobInfo.Builder.setUserInitiated(true)`), with `JobService.setNotification` for progress (required on UIDT).
3. **API 26–33** — One-shot `WorkManager` worker with `setForegroundAsync` / `getForegroundInfo` and `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (no expedited work without a foreground contract).
4. **Permissions / manifest** — `INTERNET`, `POST_NOTIFICATIONS`, `RUN_USER_INITIATED_JOBS`, `FOREGROUND_SERVICE_DATA_SYNC`; merge `androidx.work.impl.foreground.SystemForegroundService` with `foregroundServiceType="dataSync"`.
5. **Pre-flight** — `StatFs` usable space ≥ expected bytes + 8 MiB margin before enqueue; on a metered network the Settings UI must confirm once (UNMETERED job / work constraint when not confirmed).
6. **Cancel vs stop** — User cancel (notification, Settings, `STOP_REASON_USER` / `STOP_REASON_CANCELLED_BY_APP`) sets user-cancel, deletes `.partial`, and does not reschedule. System stop (constraints, task manager) interrupts without deleting `.partial` and requests job reschedule / work retry.
7. **Tests** — JVM contracts for outcome mapping, stop vs cancel, foreground promotion failure, and existing whistle downloader contracts.

## Consequences

- Downloads survive rotation and backgrounding on supported API levels with shade progress and cancel.
- Tiny-voca can adopt the same manager without duplicating policy.
- UIDT and foreground WorkManager both depend on notification permission for best UX; denial does not block the transfer but may hide progress.

## Alternatives considered

- **Raw thread in Activity** — Rejected (#261); no notification, no cancel, process-bound.
- **Expedited WorkManager only** — Rejected; API 26–30 requires `getForegroundInfo` when expedited, and quota is brittle for multi‑MB files.
- **Always delete partial on any interrupt** — Rejected; system constraint loss should allow resume/restart from partial where possible.