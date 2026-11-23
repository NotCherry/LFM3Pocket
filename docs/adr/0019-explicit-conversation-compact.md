# ADR 0019 — Conversation compact is explicit, and the transcript stays

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#221](https://github.com/NotCherry/LFM3Pocket/pull/221), [#165](https://github.com/NotCherry/LFM3Pocket/issues/165), [ADR 0003](0003-turn-write-ahead.md)

## Context

A long chat exceeds the context the phone can prefill. Dropping older turns silently would look like the app forgot, and a summary written on every reply would be a hidden memory the user did not ask for. Stage 1 history is the transcript ([ADR 0003](0003-turn-write-ahead.md)).

## Decision

Compact is a separate, user-visible step. It asks the loaded model for a short rolling summary of the conversation so far and then uses that summary, plus the most recent turns, as the live prompt.

- The private transcript is not deleted or rewritten. History still has the full conversation.
- A successful compact stores the summary and the count of summarized messages on that conversation. Opening the conversation again restores them into the live prompt ([#221](https://github.com/NotCherry/LFM3Pocket/pull/221)). Deleting the conversation deletes them with that row. Clear empties the live session only; it does not erase the stored summary.
- If the summary fails, is cancelled, or comes back empty, the previous prompt window and the previous stored summary stay as they were.
- Compact does not run by itself at the end of an ordinary reply.

## Consequences

- The user can see that a summary is in use. They can still read the original turns in history.
- There is no automatic long-term memory and no cloud memory. Deleting a conversation removes its stored summary with the rest of the row.
- A failed compact does not discard the conversation that was already stored.

## Alternatives considered

- **Silently summarize whenever the prompt is long.** Rejected. Forgetting has to be an action the user can see.
- **Replace stored turns with the summary.** Rejected. The transcript remains the record. The summary is a persisted prompt aid, not a substitute transcript.
