# Atlas UI — device visual checklist (U16)

Short protocol for signing Atlas look on a physical phone (target: RMX2170 or current Stage 1 evidence device). Pair with screenshots stored beside this file and a signed [`REPORT.md`](REPORT.md).

## Preconditions

- Install current `phone` debug APK (or CI ARM64 artifact) matching the commit under review.
- Import a small local GGUF (or use an already-imported test model). No need for Omni quality here — this checklist is **chrome**, not AT Omni quality.
- Airplane mode optional; SearXNG may stay off.

## Capture set (one screenshot each, charcoal / steel accent visible)

| Step | Screen | What to verify |
| --- | --- | --- |
| 1 | Home (`ComposeHostActivity`) | Brand-first LFM3 / Atlas charcoal; Open chat / History / Settings / About readable |
| 2 | Chat first viewport | Model import / inference steppers / SearXNG **not** dominating primary viewport; composer + transcript visible; Offline/Search pill + Settings entry present |
| 3 | Chat turn + Reasoning | Structured turn; Reasoning rail collapsed by default; expand once and note; no raw `<think>` in answer body |
| 4 | Settings host | Open via Settings / overflow; model import, inference Save (`settings_save`), SearXNG; Back to chat restores composer |
| 5 | History list + reader | Charcoal surfaces; reader uses shared turn/markdown/reasoning look vs live chat |
| 6 | Tool card (if reachable) | Bordered mono tool panel; Approve / Reject still labeled and tappable |
| 7 | Consent / camera chrome (spot-check) | Tool confirmation + camera status strings readable on Atlas surfaces |

## Operator rules

1. Record device model, build/commit SHA, APK flavor, and date in `REPORT.md`.
2. Mark each row PASS / FAIL / SKIP with a one-line note. **Do not** mark PASS without a screenshot or explicit signed observation.
3. Update [`docs/UI_ACCEPTANCE_MATRIX.md`](../../../docs/UI_ACCEPTANCE_MATRIX.md) Device visual column only after `REPORT.md` is signed.
4. Never use these screenshots alone to mark AT-01..AT-09 VERIFIED.