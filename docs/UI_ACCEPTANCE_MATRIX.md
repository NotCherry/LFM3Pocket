# Atlas UI acceptance matrix

This is the **UI** test-to-requirement map for the Atlas redesign epic (tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) / U00). It tracks **UT-01..UT-08** only.

Stage 1 product capability remains governed by [`ACCEPTANCE_MATRIX.md`](ACCEPTANCE_MATRIX.md) (AT-01..AT-09) and tracker #4. **Atlas screenshots never flip AT rows to VERIFIED.**

Style guide: [`DESIGN.md`](DESIGN.md). E2E ID / flat-transcript contract: [`UI_E2E_CONTRACT.md`](UI_E2E_CONTRACT.md). Device visual protocol: [`device-evidence/reports/ui-atlas/`](../device-evidence/reports/ui-atlas/).

## Evidence levels

| Tier | Meaning |
| --- | --- |
| **JVM** | Pure unit contracts (parser, sanitizer, flat formatter, session snapshot). |
| **EMU-FAKE** | API 30 x86_64 emulator, real UI + deterministic fake; proves integration and stable IDs. |
| **Device visual** | Physical device screenshots/notes under `device-evidence/reports/ui-atlas/` (U16). Signs Atlas look for UT rows only. |
| **MISSING** | Owner identified; no qualifying evidence yet. |
| **PARTIAL** | Some evidence exists; full UT requirement not satisfied. |
| **VERIFIED** | Every stated UT condition has linked qualifying evidence (UI epic only — still does not imply AT VERIFIED). |

## Requirement-to-owner mapping

| ID | Requirement | Owner issues | Automated / doc evidence | Device visual | Status |
| --- | --- | --- | --- | --- | --- |
| UT-01 | Atlas theme tokens applied app-wide (Home / Chat / History / Settings) | [#118](https://github.com/NotCherry/LFM3Pocket/issues/118) U01, [#120](https://github.com/NotCherry/LFM3Pocket/issues/120) U03, [#126](https://github.com/NotCherry/LFM3Pocket/issues/126) U09, [#127](https://github.com/NotCherry/LFM3Pocket/issues/127) U10, [#128](https://github.com/NotCherry/LFM3Pocket/issues/128) U11, [#129](https://github.com/NotCherry/LFM3Pocket/issues/129) U12, [#130](https://github.com/NotCherry/LFM3Pocket/issues/130) U13, [#131](https://github.com/NotCherry/LFM3Pocket/issues/131) U14 | DESIGN.md + `PocketTheme` on ComposeHost / ChatPanel / History / Settings recipe; MainActivity charcoal shell U10/U13 | U16 checklist PENDING (`device-evidence/reports/ui-atlas/REPORT.md`) | PARTIAL |
| UT-02 | Structured turns; no raw thinking / XML tags in answer body | [#122](https://github.com/NotCherry/LFM3Pocket/issues/122) U05, [#123](https://github.com/NotCherry/LFM3Pocket/issues/123) U06, [#125](https://github.com/NotCherry/LFM3Pocket/issues/125) U08 | JVM: `ChatSessionTest` structured+flat parity + think-tag strip; `ThinkingSanitizerTest`; `FlatTranscriptTest` / `FlatTranscriptFormatterTest`. U08 `ChatTurnList` + flat host on `chat_transcript`. EMU-FAKE: `ChatUiE2eTest.scriptedThinkTagsDoNotAppearInFlatTranscript` (U15; needs emulator CI) | Optional transcript screenshot PENDING | PARTIAL |
| UT-03 | Markdown + sources + media chips readable | [#124](https://github.com/NotCherry/LFM3Pocket/issues/124) U07, [#125](https://github.com/NotCherry/LFM3Pocket/issues/125) U08, [#129](https://github.com/NotCherry/LFM3Pocket/issues/129) U12 | JVM: `MarkdownParserTest`. `MarkdownBody` + ChatTurnList/history shared renderer landed | Device read of sample turn PENDING | PARTIAL |
| UT-04 | Reasoning rail collapse/expand; stream cursor preserved | [#123](https://github.com/NotCherry/LFM3Pocket/issues/123) U06, [#125](https://github.com/NotCherry/LFM3Pocket/issues/125) U08, [#132](https://github.com/NotCherry/LFM3Pocket/issues/132) U15 | JVM: sanitizer extracts reasoning + streaming cleaned partial/`▌` in flat (`ChatSessionTest`). Rail UI + flat contract U08/U15 | Device rail expand note PENDING | PARTIAL |
| UT-05 | Chat hero: model / search / settings out of primary viewport | [#127](https://github.com/NotCherry/LFM3Pocket/issues/127) U10, [#130](https://github.com/NotCherry/LFM3Pocket/issues/130) U13 | Settings host + `settings_open`; E2E adapted to open Settings before `import_*` / `web_search` / inference fields (U15) | Chat first-viewport screenshot PENDING | PARTIAL |
| UT-06 | History list/reader parity with live chat rendering | [#129](https://github.com/NotCherry/LFM3Pocket/issues/129) U12 | Shared turn/markdown/reasoning components; history E2E classes retained (EMU-FAKE pending CI) | Side-by-side history vs chat PENDING | PARTIAL |
| UT-07 | Tool card + consent chrome Atlas; Approve/Reject IDs stable | [#128](https://github.com/NotCherry/LFM3Pocket/issues/128) U11, [#131](https://github.com/NotCherry/LFM3Pocket/issues/131) U14 | Stable `tool_*` IDs in `ids.xml`; `ToolChatE2eTest` / `ToolConsentE2eTest` (EMU-FAKE pending CI) | Tool/consent screenshots PENDING | PARTIAL |
| UT-08 | Existing Stage 1 chat E2E green (IDs + flat transcript contract) | [#121](https://github.com/NotCherry/LFM3Pocket/issues/121) U04, [#132](https://github.com/NotCherry/LFM3Pocket/issues/132) U15 | `UI_E2E_CONTRACT.md` + JVM `FlatTranscriptFormatterTest`; U15 adaptations in [#137](https://github.com/NotCherry/LFM3Pocket/pull/137). **EMU-FAKE green on main:** [Actions run 43703089138](https://github.com/NotCherry/LFM3Pocket/actions/runs/43703089138) (post-#137 merge). Prior PR evidence: [run 43629362929](https://github.com/NotCherry/LFM3Pocket/actions/runs/43629362929) | N/A (CI evidence) | VERIFIED |

## Tracker closeout status (Phase 4)

| Code | Issue | Closeout note |
| --- | --- | --- |
| U15 | [#132](https://github.com/NotCherry/LFM3Pocket/issues/132) | Closed via [#137](https://github.com/NotCherry/LFM3Pocket/pull/137); Stage 1 EMU CI green on main ([run 43703089138](https://github.com/NotCherry/LFM3Pocket/actions/runs/43703089138)). |
| U16 | [#133](https://github.com/NotCherry/LFM3Pocket/issues/133) | Protocol + PENDING report under `device-evidence/reports/ui-atlas/`. **Operator device run still required** — leave open. |
| U17 | [#134](https://github.com/NotCherry/LFM3Pocket/issues/134) | README navigation + RELEASE Atlas-vs-AT note + matrix/DESIGN links refreshed. |

## Maintenance contract

1. Every Atlas UI PR identifies its **UT** ID(s) (and AT IDs if behavior overlaps Stage 1).
2. Update **Automated / doc evidence** only after the listed test or doc lands; link CI run or JVM class names.
3. **Device visual** updates come from U16 protocol output under `device-evidence/reports/ui-atlas/`. Device visuals may move a UT row to PARTIAL/VERIFIED for *look*, but must not be used to mark AT-01..AT-09 VERIFIED.
4. If chat E2E or ID discovery regresses, demote UT-08 (and related rows) and reopen U15 / owning issues.
5. Keep this file’s UT-01..UT-08 rows present with owners and explicit status (mirror Stage 1 matrix maintenance spirit in short form).
6. Prefer EMU-FAKE for integration claims; never claim Stage 1 release readiness from Atlas chrome alone.

## Issue index (U00–U17)

| Code | GitHub | Title |
| --- | --- | --- |
| U00 | [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) | Tracker: Atlas UI redesign |
| U01 | [#118](https://github.com/NotCherry/LFM3Pocket/issues/118) | `docs/DESIGN.md` Atlas style guide |
| U02 | [#119](https://github.com/NotCherry/LFM3Pocket/issues/119) | `docs/UI_ACCEPTANCE_MATRIX.md` |
| U03 | [#120](https://github.com/NotCherry/LFM3Pocket/issues/120) | `PocketTheme` Atlas (Compose) |
| U04 | [#121](https://github.com/NotCherry/LFM3Pocket/issues/121) | E2E transcript / semantics contract |
| U05 | [#122](https://github.com/NotCherry/LFM3Pocket/issues/122) | Structured chat snapshot |
| U06 | [#123](https://github.com/NotCherry/LFM3Pocket/issues/123) | Thinking / token sanitizer |
| U07 | [#124](https://github.com/NotCherry/LFM3Pocket/issues/124) | Markdown subset renderer |
| U08 | [#125](https://github.com/NotCherry/LFM3Pocket/issues/125) | Atlas `ChatTurnList` + Reasoning rail |
| U09 | [#126](https://github.com/NotCherry/LFM3Pocket/issues/126) | Home Atlas |
| U10 | [#127](https://github.com/NotCherry/LFM3Pocket/issues/127) | Chat shell declutter |
| U11 | [#128](https://github.com/NotCherry/LFM3Pocket/issues/128) | Composer + tool/media Atlas |
| U12 | [#129](https://github.com/NotCherry/LFM3Pocket/issues/129) | History Atlas + shared renderer |
| U13 | [#130](https://github.com/NotCherry/LFM3Pocket/issues/130) | Settings screen |
| U14 | [#131](https://github.com/NotCherry/LFM3Pocket/issues/131) | Consent + camera chrome pass |
| U15 | [#132](https://github.com/NotCherry/LFM3Pocket/issues/132) | E2E adaptation sweep |
| U16 | [#133](https://github.com/NotCherry/LFM3Pocket/issues/133) | Device visual checklist |
| U17 | [#134](https://github.com/NotCherry/LFM3Pocket/issues/134) | Docs closeout |
