# ADR 0016 — Runtime policy mutations stay on the owner thread

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-20
- Related: [#238](https://github.com/NotCherry/LFM3Pocket/pull/238), [#200](https://github.com/NotCherry/LFM3Pocket/issues/200), [#204](https://github.com/NotCherry/LFM3Pocket/pull/204), [ADR 0006](0006-voice-actions-loop.md)

## Context

Runtime policy decides the foreground notification and whether to unload the model, cancel the current turn, or refuse new work. Those flags were reachable from the main thread, from inference callbacks, and from voice and consent events. Two updates could interleave and publish a notice, or an unload, that matched neither update.

## Decision

The policy object is owned by the thread that created it. Every read and every mutation runs on that thread. Android wiring posts those calls onto the main thread, which is the owner. A call from any other thread fails immediately and does not change the flags.

Voice, consent, and unload requests that affect the policy go through that same serialization. Unload still cancels an in-flight listen and a pending spoken confirmation before the model is released ([ADR 0006](0006-voice-actions-loop.md)).

Decode stays off this thread. The decision is about policy state, not about moving inference onto the main thread.

## Consequences

- The notification and the unload decision observe one consistent set of flags.
- Callers that are not already on the owner thread post their update. They do not touch the policy directly.
- A bug that calls the policy off-thread surfaces as a failed call instead of a torn notification.

## Alternatives considered

- **Lock every field.** Rejected. A lock still allows a caller to read a half-applied decision if two logical updates interleave, and it hides which thread is supposed to own the state.
- **Move policy onto the inference worker.** Rejected. The notification and the activity lifecycle already live on the main thread. The owner follows that thread.
