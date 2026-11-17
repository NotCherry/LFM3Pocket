# ADR 0007: Voice reminders via inexact WorkManager

## Status

Accepted (Voice V2).

## Context

Issue [#159](https://github.com/NotCherry/LFM3Pocket/issues/159) adds `set_reminder` for local notification reminders on the voice/chat path. Timer and clock alarms remain delegated to the system clock app ([#37](https://github.com/NotCherry/LFM3Pocket/issues/37), [#38](https://github.com/NotCherry/LFM3Pocket/issues/38)). Play policy discourages `SCHEDULE_EXACT_ALARM` unless exact timing is essential.

## Decision

1. **Tool:** `set_reminder` with `when_epoch_sec`, `title`, and `body`; structural bounds in `ToolRegistry`; future-time checks in `ReminderController`.
2. **Risk:** `CONFIRM` via existing `ToolCoordinator` / voice confirm loop (unchanged from V1).
3. **Execution:** `OneTimeWorkRequest` with initial delay derived from wall clock (inexact). `ReminderNotifyWorker` posts to the dedicated `hanami_reminder` notification channel. Unique work id per scheduled reminder survives process death without holding in-memory state.
4. **Permission:** On API 33+, `POST_NOTIFICATIONS` is required before propose/execute; no new exact-alarm permissions.

## Consequences

- `androidx.work:work-runtime` added to the app module.
- JVM tests use a fake `ReminderScheduler`; WorkManager is not exercised on the desktop test JVM.
- OEM notification delay remains acceptable for inexact reminders; exact alarms are out of scope unless a future ADR justifies them.

## References

- Plan: [voice-actions.md](../plans/voice-actions.md) (Voice V2)
- Parent voice loop: [ADR 0006](0006-voice-actions-loop.md)