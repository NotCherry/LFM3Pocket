# ADR 0007: Voice reminders (exact alarm when allowed, inexact fallback)

## Status

Accepted (Voice V2).

## Context

Issue [#159](https://github.com/NotCherry/LFM3Pocket/issues/159) adds `set_reminder` for local notification reminders on the voice/chat path. Timer and clock alarms remain delegated to the system clock app ([#37](https://github.com/NotCherry/LFM3Pocket/issues/37), [#38](https://github.com/NotCherry/LFM3Pocket/issues/38)). The model must not compute Unix epochs; the app converts `in_minutes` or local ISO `at` fields.

## Decision

1. **Tool:** `set_reminder` with `in_minutes` **or** `at` (local `yyyy-MM-ddTHH:mm`), plus `title` (1..100) and `body` (0..500). Optional `cancel_reminder` by title (`CONFIRM`). Duplicates (same title + time) are rejected.
2. **Consent:** Confirm UI and voice read-back show a **localized `when:`** line from `ReminderDescriptions`, not raw epoch seconds.
3. **Execution:** Persist pending rows in `ReminderStore`. Prefer **`AlarmManager.setExactAndAllowWhileIdle`** when `canScheduleExactAlarms()` is true (`SCHEDULE_EXACT_ALARM` in manifest). Otherwise enqueue **inexact WorkManager** and tell the user delivery may slip under Doze.
4. **Notifications:** Dedicated `hanami_reminder` channel; stable per-reminder notification id. `POST_NOTIFICATIONS` is requested in-context on first reminder proposal (API 33+).
5. **Resilience:** `BOOT_COMPLETED` re-queues persisted reminders. **Force-stop** still clears OS alarms until the user opens Hanami again (`Application.onCreate` reschedule). This limitation is surfaced in schedule messages and plan docs.

## Consequences

- `androidx.work:work-runtime`, alarm receivers, and boot receiver add manifest surface.
- Without exact-alarm grant, reminders may be **hours late** under Doze; copy and consent text say so.
- After user force-stop, reminders do not fire until Hanami is opened once to reschedule from disk.

## References

- Plan: [voice-actions.md](../plans/voice-actions.md) (Voice V2)
- Parent voice loop: [ADR 0006](0006-voice-actions-loop.md)