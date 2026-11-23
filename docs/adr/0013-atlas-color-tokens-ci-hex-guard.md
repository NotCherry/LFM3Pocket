# ADR 0013 — Atlas color tokens are normative, and CI enforces them

- Status: Accepted
- Date: 2026-11-23
- Related: [#258](https://github.com/NotCherry/LFM3Pocket/pull/258)

## Context

[`DESIGN.md`](../DESIGN.md) §2 is the Stillwater / Atlas color spec. Its hex values are normative, and the implementation checklist already told authors to take colors from Atlas tokens / `PocketTheme` rather than one-off hex. That line was a review convention. A pull request could still paint UI with a raw color literal and stay green, and nothing checked that the Android resource palette still matched the spec.

[#258](https://github.com/NotCherry/LFM3Pocket/pull/258) made `AtlasColors` / `PocketTheme` the single source of colors in code and kept the Android resource palette on the same values. This record is the decision that the rule is now machine-enforced.

## Decision

DESIGN.md is the normative source of Hanami UI color values. UI code uses Atlas tokens (`AtlasColors` / `PocketTheme`) and the Android resource palette that mirrors them. A raw hex literal is not a source of color.

The resource palette must stay aligned with the hex values in DESIGN.md. Drift between the spec and that palette fails the build.

Two checks enforce the rule, both in the Stage 1 unit job:

- A CI hex guard rejects raw hex colors in UI code outside the token sources.
- A JVM palette-sync test verifies the resource palette against the normative hex values in DESIGN.md.

An explicit allow mark is the escape hatch for a literal that is not a UI color choice. Unmarked raw hex in UI code fails the hex guard.

## Consequences

- A new or changed color updates DESIGN.md and the Atlas tokens first, and keeps the resource palette in step, in the same change. Otherwise the hex guard or the palette-sync test fails the Stage 1 unit job.
- Authors and agents get that failure on the pull request, instead of waiting for review to spot a one-off hex.
- [`docs/theme/README.md`](../theme/README.md) is the agent-facing map of the token layers (spec, Compose theme, resource palette) and of this rule.

## Alternatives considered

- **Checklist only.** This was the previous state: DESIGN.md asked for tokens, and review was the only check. Rejected: one-off hex could merge on a green build.
- **Code tokens as the only source, with DESIGN.md as commentary.** Rejected. The spec’s hex values stay normative so the resource palette has one table to match, and a color change is visible in the design doc before it spreads through code.
- **No escape hatch.** Rejected. A few literals are not UI color choices. An explicit allow mark keeps those lines reviewable without turning the guard off.
