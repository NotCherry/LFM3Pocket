# ADR 0014 — Calendar tools write only the calendar the user picked

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#222](https://github.com/NotCherry/LFM3Pocket/pull/222), [#161](https://github.com/NotCherry/LFM3Pocket/issues/161), [ADR 0006](0006-voice-actions-loop.md), [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md)

## Context

Calendar read and write is a voice and chat capability: list events in a bounded window, and create, update, or delete an event. Calendar data is both a side effect and a source of text the model did not write. A silent write into whatever calendar the provider calls the default would change the user’s schedule without a target they chose.

## Decision

1. **Allowlist.** `list_events`, `create_event`, `update_event`, and `delete_event` are the calendar tools. Argument bounds live in [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md).
2. **Permissions.** Listing requires read access. Creating, updating, and deleting require write access. Both are requested as ordinary runtime permissions, not held in reserve for a background scan.
3. **Picked calendar.** Writes also require a calendar the user picked in Settings. There is no silent default. A create must name that same calendar. Without the pick, or without write access, the write does not run.
4. **Fail closed.** Permission and the picked calendar are checked before a proposal and again immediately before execution. Revoking access between those two moments denies the action. A missing or stale event id denies update and delete.
5. **Untrusted content.** Listed events and calendar tool results are untrusted data. They cannot authorize a later action. Confirmation read-back for a write includes the title and the local time.
6. **Copy.** The picker and the tool descriptions ship in English and Polish.

Spoken confirmation, when the user is on the voice loop, still uses the closed lexicon in [ADR 0006](0006-voice-actions-loop.md).

## Consequences

- The model cannot land an event in a calendar the user did not choose.
- Calendar text that comes back into the prompt is data, not authority.
- Reminders stay on the notification path ([ADR 0007](0007-voice-reminders-workmanager.md)). They are not calendar events.

## Alternatives considered

- **Write the system default calendar when the user has not picked one.** Rejected. The default is not a choice the user made inside Hanami.
- **Treat a listed event as trusted enough to approve a follow-up tool.** Rejected. Event titles and notes are an injection path, same as other retrieved text.
