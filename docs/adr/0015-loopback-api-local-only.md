# ADR 0015 — The loopback API is local, bounded, and cannot approve

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-20
- Related: [#147](https://github.com/NotCherry/LFM3Pocket/pull/147), [#205](https://github.com/NotCherry/LFM3Pocket/pull/205), [#189](https://github.com/NotCherry/LFM3Pocket/issues/189), [`docs/plans/background-mode.md`](../plans/background-mode.md)

## Context

A program on the same phone can submit a prompt while the model is resident. That listener landed as an opt-in loopback socket ([#147](https://github.com/NotCherry/LFM3Pocket/pull/147)): it could ask, and it could not approve a tool. [#205](https://github.com/NotCherry/LFM3Pocket/pull/205) is the decision to treat the listener as a local security boundary, not only as a convenience socket. An unbounded or unauthenticated request, or a request that could approve, would turn the socket into a second user.

## Decision

1. **Off, and loopback only.** The listener is off until the user turns it on. It binds to loopback and drops a peer that is not loopback. It does not listen on the LAN.
2. **Caller token.** While it is on, every request — including `GET /health` — must carry the per-install bearer token in `Authorization: Bearer …`. Missing or wrong credentials get `401` before routing. The token is shown only through an explicit in-app action. It is not written to the log.
3. **No approve.** The listener can submit a prompt and answer a health check. The approve route is forbidden. A request cannot become user authority for a tool. Consent stays on the phone UI.
4. **Bounded intake.** Header size, body size, how long a read may take, and how many clients may be in flight are capped. A client past the cap is rejected. For `POST /v1/ask`, a body that starts with `{` is parsed as JSON and must contain a string `prompt` field. A malformed JSON object is rejected. Any other non-blank body is accepted as the prompt text itself. Submitted text is an ordinary prompt, with the same trust as text typed in chat.
5. **Out of scope.** Token streaming, an MCP skin, a companion on another device, and a shell bridge are not this decision.

## Consequences

- Another app on the phone can send a prompt only when the listener is on and it has the token. It still cannot approve a tool.
- A hostile or accidental flood is rejected instead of parsed without limit.
- Cross-device access needs a later decision. This record does not allow a LAN bind.

## Alternatives considered

- **Loopback with no token, because the port is local.** Rejected in #205. Other apps on the device can open loopback.
- **Let the socket approve when the prompt text says so.** Rejected. Retrieved and submitted text never authorizes an action. Approve stays on the consent UI.
