# ADR 0017 — Message tools open a composer; Hanami does not send

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#216](https://github.com/NotCherry/LFM3Pocket/pull/216), [#160](https://github.com/NotCherry/LFM3Pocket/issues/160), [ADR 0006](0006-voice-actions-loop.md)

## Context

Voice and chat can prepare a text message. Sending it from inside Hanami would need SMS or another app’s private API, and a confirmed tool call could deliver text the user never saw in that app. The message the model asks to send and the message that actually leaves the phone have to be the same text, finished by the user.

## Decision

1. **Two steps.** `draft_message` stores an in-memory draft (SMS, or a share to an allowlisted app). `send_message` takes that draft’s id only. It cannot supply a new body.
2. **No silent send.** SMS opens the system composer. Share opens the allowlisted app’s composer. The user sends or abandons the message there. If that app is missing, the action fails and nothing is sent.
3. **Consent.** Both steps are confirmations, same as other side effects ([ADR 0006](0006-voice-actions-loop.md)).
4. **Not history.** Drafts are not part of the conversation store. They are dropped with the process.

Argument bounds stay in the tool validator. Share targets are an allowlist, not any installed package.

## Consequences

- Hanami never delivers an SMS or a chat message by itself.
- A send after the draft is gone fails closed.
- Apps outside the share allowlist are not a message target.

## Alternatives considered

- **Send SMS directly after confirm.** Rejected. The composer is the user’s last look, and the app does not take the SMS-send role.
- **Let `send_message` carry its own body.** Rejected. The confirmed draft and the delivered text would be free to diverge.
