# ADR 0013 — Atlas color tokens are normative, and CI enforces them

- Status: Accepted
- Date: 2026-11-23
- Related: [#258](https://github.com/NotCherry/LFM3Pocket/pull/258)

## Context

[`DESIGN.md`](../DESIGN.md) §2 is the Stillwater / Atlas color spec. Its hex values are normative, and the implementation checklist already told authors to take colors from Atlas tokens / `PocketTheme` rather than one-off hex. That line was a review convention. A pull request could still paint UI with a raw color literal and stay green.

[#258](https://github.com/NotCherry/LFM3Pocket/pull/258) made `AtlasColors` / `PocketTheme` the Compose source for UI colors, including `inkSoft`, and added the CI guard. That pull request did not edit `colors.xml`. Soft chalk (`still_soft`) was already the spec value; the guard now requires it to stay in step with `inkSoft` and `StillwaterUi.SOFT`. It does not claim the rest of the resource palette was aligned. This record is the decision that the rule is machine-enforced, and the exact scope of that enforcement. The checks do not parse the DESIGN.md color table, and they do not compare every `colors.xml` entry to the spec.

## Decision

DESIGN.md is the normative source of Hanami UI color values. UI code takes color from Atlas tokens (`AtlasColors` / `PocketTheme`) and from the Android resource palette that mirrors them. A raw hex literal is not a source of color.

The Stage 1 unit job enforces the parts below. Both checks run there.

### CI hex guard

`.github/scripts/check-ui-tokens.py` scans every `.kt` and `.java` file under `app/src/main/java/com/notcherry/lfm3pocket/`, not only UI packages. It rejects an 8-digit `0xAARRGGBB` literal, including `Color(0x…)`, any alpha, any case. It does not see `"#RRGGBB"`, `Color.parseColor`, layout XML, or `colors.xml`.

Lines whose first non-space character is `//` or `*` are skipped. A same-line `ui-tokens: allow` is the escape hatch for a literal that is not a UI color choice. Unmarked literals fail the guard.

Three files are allowlisted and may contain those literals: `AtlasColors.kt`, `StillwaterUi.java`, and `RuntimeNotifications.java` (notification accent at line 19, not a token source).

The same script fails the build when soft chalk drifts: `AtlasColors.inkSoft`, `StillwaterUi.SOFT`, and `colors.xml` `still_soft` must agree with each other and with the soft-chalk value the script expects. Other `colors.xml` entries (`still_surface`, `still_ink`, `still_candle`, `still_overlay`, `still_highlight`, and the rest) are not compared. It also rejects the old tool-panel radius wording (6–8dp) and requires the DESIGN.md bordered-panel clause to say 0–2dp.

### Palette pin

`AtlasPaletteSyncTest` pins the `StillwaterUi` constants to hex literals hardcoded in the test (a hand copy of the DESIGN.md §2 table). It does not read DESIGN.md or `colors.xml`. Editing the spec, or editing `colors.xml`, without editing `StillwaterUi` and those test literals, does not fail this test. Compose `AtlasColors` is not on the pure JVM classpath, so this test does not pin it. Soft-chalk sync for `still_soft` is only in the script above.

## Follow-ups

- Extend `AtlasPaletteSyncTest` (or a sibling check) to read the DESIGN.md §2 color table and the full `colors.xml` `still_*` palette, so spec ↔ resource drift fails the build.
- Extend `check-ui-tokens.py` to reject `"#RRGGBB"` string literals and `Color.parseColor` outside the same allowlist / `ui-tokens: allow` escape hatch.

## Consequences

- A new or changed color updates DESIGN.md, the Atlas tokens, and the resource palette in the same change. The hex guard fails an unmarked 8-digit literal outside the allowlist. The soft-chalk check fails when `inkSoft`, `StillwaterUi.SOFT`, and `still_soft` disagree. The JVM test fails when `StillwaterUi` no longer matches the literals copied into `AtlasPaletteSyncTest`. A DESIGN.md-only edit of another token, or a drift in the rest of `colors.xml`, stays green.
- Authors and agents get the failure the guard does cover on the pull request, instead of waiting for review to spot that class of one-off hex.
- [`docs/theme/README.md`](../theme/README.md) is the agent-facing map of the token layers (spec, Compose theme, resource palette) and of this rule.

## Alternatives considered

- **Checklist only.** This was the previous state: DESIGN.md asked for tokens, and review was the only check. Rejected: one-off hex could merge on a green build.
- **Code tokens as the only source, with DESIGN.md as commentary.** Rejected. The spec’s hex values stay normative so a color change is visible in the design doc before it spreads through code. The machine checks pin `StillwaterUi` and soft chalk; they do not parse the spec.
- **No escape hatch.** Rejected. A few literals are not UI color choices. A same-line `ui-tokens: allow` keeps those lines reviewable without turning the guard off.
- **Parsing DESIGN.md and the whole of `colors.xml` in CI.** Deferred to [Follow-ups](#follow-ups); not part of the enforcement that landed in #258.
