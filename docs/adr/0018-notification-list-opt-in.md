# ADR 0018 — Notification listing is opt-in, allowlisted, and untrusted

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#220](https://github.com/NotCherry/LFM3Pocket/pull/220), [#162](https://github.com/NotCherry/LFM3Pocket/issues/162), [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md)

## Context

Reading notifications lets the model summarize what other apps posted. Those posts are untrusted text, and the listener is a powerful system role. A default-on listener, or a result the model could treat as an instruction, would turn other apps’ notifications into actions.

## Decision

1. **Off.** Listing notifications is off until the user turns on an in-app master switch and grants the system notification-listener role.
2. **Allowlist.** An empty per-app allowlist denies every package. Results include only allowed packages.
3. **Untrusted.** Titles and bodies are untrusted data. They cannot authorize a tool. The tool does not tap, dismiss, or otherwise act on a notification.
4. **Confirm.** A listing still requires confirmation before it runs.

Bounds and the result marker are in [`ANDROID_TOOLS.md`](../ANDROID_TOOLS.md).

## Consequences

- Installing Hanami does not expose the notification shade to the model.
- A notification that contains tool-shaped text is still data.
- There is no “act on this notification” tool in this decision.

## Alternatives considered

- **On for every package once the listener role is granted.** Rejected. The system role and the set of apps the model may read are separate choices.
- **Dismiss or open a notification from the tool.** Rejected. Listing is read-only.
