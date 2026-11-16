# ADR 0004 — On-device crash log

## Status

Accepted. [PR #170](https://github.com/NotCherry/LFM3Pocket/pull/170), 2026-11-16.

## Context

Process death during a vision turn is hard to explain from the chat alone. The interrupted-turn recovery in [ADR 0003](0003-turn-write-ahead.md) restores the conversation, but it does not say why the process died. Timing breadcrumbs already stay on device. They do not record an uncaught exception or the platform’s reason for the last process exit.

A log that left the phone on its own would conflict with the app’s local-only diagnostics rule.

## Decision

The process keeps one crash log in app-private storage.

- At startup, the process installs a handler that appends the last uncaught exception (thread name and stack) and then forwards to the previous handler. A failure inside the log must not replace the original crash.
- On the same startup, on Android 11 and newer, the log also records recent historical process-exit reasons reported by the platform (including a native crash, low memory, and an ANR). Where the platform supplies a trace, a limited excerpt is kept. Collection is best-effort.
- New records are written at the front. The file is capped.

Settings shows the log, can clear it, and can hand the text to the system share sheet. The app does not upload the log. The text leaves the device only when the person chooses a share target. Sharing uses the text itself; the log is not a telemetry channel.

This log is separate from the conversation store and from the timing breadcrumb file.

## Consequences

- A later session can show why a previous process died without a desktop attached.
- Stack traces and platform exit descriptions can contain paths and other local detail. They stay on the phone until an explicit share.
- The log is diagnostic. It does not restore a chat turn; [ADR 0003](0003-turn-write-ahead.md) does that.
- Devices older than Android 11 record uncaught exceptions only. Exit reasons are absent there.
