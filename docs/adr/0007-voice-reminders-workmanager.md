# ADR 0007 — Local reminders via inexact WorkManager

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#219](https://github.com/NotCherry/LFM3Pocket/pull/219), [#159](https://github.com/NotCherry/LFM3Pocket/issues/159), [ADR 0006](0006-voice-actions-loop.md), [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md)

## Context

Voice and chat can ask Hanami to remind the user later with a local notification. Timer and clock alarms stay delegated to the system clock app. A reminder has to survive process death, and it must not take the exact-alarm permission.

An unmerged branch later preferred exact alarms with an inexact fallback. That revision never landed. This record follows the behavior on `main`.

## Decision

1. **Tool.** `set_reminder` is an allowlisted tool. It schedules a local notification. Argument bounds live in [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md). The model does not gain a second authorization path: the proposal is still a model proposal.
2. **Consent.** The reminder requires confirmation, with the same read-back rule as other side effects ([ADR 0006](0006-voice-actions-loop.md)).
3. **Schedule.** Delivery is an inexact WorkManager job. The app does not request exact-alarm permission. Posting the notification requires the notification permission on Android 13 and newer.
4. **Not a calendar event.** Reminders do not write the user’s calendar. Calendar read and write are a separate decision ([ADR 0014](0014-calendar-tools-picked-calendar.md)).

## Consequences

- A reminder can be late under Doze. That slip is accepted in exchange for not holding exact-alarm privilege.
- The scheduled work outlives the process. It does not outlive a force-stop until the user opens the app again and the platform allows the work to run.
- Clock alarms remain the system clock app’s job.

## Alternatives considered

- **Exact alarms, with WorkManager only as a fallback.** Drafted on an unmerged branch. Rejected for `main`: the shipped product does not ask for exact-alarm access.
- **Store the reminder as a calendar event.** Rejected. That needs calendar permission and a picked calendar, and it is a different product action.
