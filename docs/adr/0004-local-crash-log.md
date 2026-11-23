# ADR 0004 — On-device crash log

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-16
- Related: [#170](https://github.com/NotCherry/LFM3Pocket/pull/170), [`DIAGNOSTICS.md`](../DIAGNOSTICS.md), [ADR 0003](0003-turn-write-ahead.md)

## Context

Process death during a turn is hard to explain from the chat alone. Interrupted-turn recovery ([ADR 0003](0003-turn-write-ahead.md)) restores the conversation, but it does not say why the process died. Timing breadcrumbs already stay on device. They do not record an uncaught exception or the platform’s reason for the last process exit.

A log that left the phone on its own would conflict with the local-only diagnostics rule.

## Decision

The process keeps one crash log in app-private storage.

- At startup, the process installs a handler that appends the last uncaught exception and then forwards to the previous handler. A failure inside the log must not replace the original crash.
- On the same startup, on Android 11 and newer, the log also records recent historical process-exit reasons reported by the platform (including a native crash, low memory, and an ANR). Where the platform supplies a trace, a limited excerpt is kept. Collection is best-effort.
- The file is capped. Settings can show the log, clear it, and hand the text to the system share sheet. The app does not upload the log. The text leaves the device only when the person chooses a share target.

This log is separate from the conversation store and from the timing breadcrumb file. Operational detail lives in [`DIAGNOSTICS.md`](../DIAGNOSTICS.md).

## Consequences

- A later session can show why a previous process died without a desktop attached.
- Stack traces and platform exit descriptions can contain paths and other local detail. They stay on the phone until an explicit share.
- The log is diagnostic. It does not restore a chat turn.
- Devices older than Android 11 record uncaught exceptions only. Exit reasons are absent there.

## Alternatives considered

- **Upload the log, or attach it to the next launch automatically.** Rejected. Diagnostics stay on the device unless the person shares them.
- **Reuse the timing breadcrumb file.** Rejected. Exit reasons and stacks are a different record from load and token timings, and they need their own cap and clear action.
