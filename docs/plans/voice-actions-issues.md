# Voice actions — issue index

Created from the execution plan in [`voice-actions.md`](voice-actions.md).

**Epic:** [#167 Voice actions](https://github.com/NotCherry/LFM3Pocket/issues/167)
**Plan PR:** [#149 Plan: voice actions (Omni audio-in + first tool set)](https://github.com/NotCherry/LFM3Pocket/pull/149) (merged 2026-11-18)
**V1 implementation PR:** [#180 Voice V1: hands-free loop (listen, Omni audio-in, speech, confirm)](https://github.com/NotCherry/LFM3Pocket/pull/180) (merged 2026-11-17)

## Update (2026-11-23 docs audit)

The table below is the 2026-11-18 snapshot. It is not rewritten. Since that date:

- [#159](https://github.com/NotCherry/LFM3Pocket/issues/159) reminders landed in [#219](https://github.com/NotCherry/LFM3Pocket/pull/219). The decision on `main` is inexact WorkManager, not exact alarms ([ADR 0007](../adr/0007-voice-reminders-workmanager.md)).
- [#160](https://github.com/NotCherry/LFM3Pocket/issues/160) messages landed in [#216](https://github.com/NotCherry/LFM3Pocket/pull/216) ([ADR 0017](../adr/0017-message-tools-open-composer.md)).
- [#161](https://github.com/NotCherry/LFM3Pocket/issues/161) calendar tools landed in [#222](https://github.com/NotCherry/LFM3Pocket/pull/222) ([ADR 0014](../adr/0014-calendar-tools-picked-calendar.md)).
- [#162](https://github.com/NotCherry/LFM3Pocket/issues/162) notification listing landed in [#220](https://github.com/NotCherry/LFM3Pocket/pull/220) ([ADR 0018](../adr/0018-notification-list-opt-in.md)).
- [#163](https://github.com/NotCherry/LFM3Pocket/issues/163) assistant gesture landed in [#218](https://github.com/NotCherry/LFM3Pocket/pull/218) ([ADR 0006](../adr/0006-voice-actions-loop.md)).
- [#164](https://github.com/NotCherry/LFM3Pocket/issues/164) Vulkan spike landed in [#217](https://github.com/NotCherry/LFM3Pocket/pull/217). CPU stays the default ([`VOICE_GPU_NPU_SPIKE_164.md`](../VOICE_GPU_NPU_SPIKE_164.md)).
- [#165](https://github.com/NotCherry/LFM3Pocket/issues/165) compact landed in [#221](https://github.com/NotCherry/LFM3Pocket/pull/221) ([ADR 0019](../adr/0019-explicit-conversation-compact.md)).
- [#166](https://github.com/NotCherry/LFM3Pocket/issues/166) local documents landed in [#223](https://github.com/NotCherry/LFM3Pocket/pull/223) ([ADR 0020](../adr/0020-untrusted-local-documents.md)).
- [#204](https://github.com/NotCherry/LFM3Pocket/pull/204) merged. A failed confirm pass cannot approve, and unload cancels listen and confirmation before the model is released ([ADR 0006](../adr/0006-voice-actions-loop.md)).

## Audit snapshot (2026-11-18, branch `ao/voice-epic/root`)

| # | Title | Phase | State | Notes |
| --- | --- | --- | --- | --- |
| [167](https://github.com/NotCherry/LFM3Pocket/issues/167) | Voice actions | epic | OPEN | Tracking issue. Owner follow-ups still required: labels/milestones could not be created/applied by bot (HTTP 403). |
| [153](https://github.com/NotCherry/LFM3Pocket/issues/153) | Mic FGS listen session (PTT + shade Talk) | V1 | CLOSED | Delivered in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180). |
| [154](https://github.com/NotCherry/LFM3Pocket/issues/154) | One-pass Omni audio-in + no-audio disable | V1 | CLOSED | Delivered in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180). |
| [155](https://github.com/NotCherry/LFM3Pocket/issues/155) | Spoken output: Omni speech + Polish system TTS fallback | V1 | CLOSED | Delivered in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180). |
| [156](https://github.com/NotCherry/LFM3Pocket/issues/156) | Voice confirmation (exact read-back + tak/nie) | V1 | CLOSED | Delivered in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180). |
| [157](https://github.com/NotCherry/LFM3Pocket/issues/157) | Voice tool prompt card + pl/en strings | V1 | CLOSED | Delivered in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180). |
| [158](https://github.com/NotCherry/LFM3Pocket/issues/158) | Arm64 voice-loop device checklist | V1 | CLOSED | Checklist/template landed ([`docs/VOICE_LOOP_CHECKLIST.md`](../VOICE_LOOP_CHECKLIST.md)); **no arm64 PASS/FAIL/NOT_RUN evidence recorded yet** (device identity/APK SHA/outcomes still needed). |
| [159](https://github.com/NotCherry/LFM3Pocket/issues/159) | set_reminder via notification + WorkManager | V2 | OPEN | No PR found. Depends on existing timer/alarm tools and V1 confirm path. |
| [160](https://github.com/NotCherry/LFM3Pocket/issues/160) | draft_message + send_message (intent path) | V3 | OPEN | No PR found. |
| [161](https://github.com/NotCherry/LFM3Pocket/issues/161) | Calendar read/write tools + calendar picker | V4 | OPEN | No PR found. |
| [162](https://github.com/NotCherry/LFM3Pocket/issues/162) | Notification listener + list_notifications | V5 | OPEN | No PR found. |
| [163](https://github.com/NotCherry/LFM3Pocket/issues/163) | Default Android assistant role (coordinate, do not duplicate) | V6 | OPEN | No PR found. Coordinate with existing/in-progress assistant-entry quick-wins; do not duplicate. |
| [164](https://github.com/NotCherry/LFM3Pocket/issues/164) | GPU/NPU acceleration spike (Vulkan first) | next | OPEN | No PR found. |
| [165](https://github.com/NotCherry/LFM3Pocket/issues/165) | Rolling memory summarization | next | OPEN | No PR found. |
| [166](https://github.com/NotCherry/LFM3Pocket/issues/166) | Local document chat (SAF, untrusted) | next | OPEN | No PR found. |
| [150](https://github.com/NotCherry/LFM3Pocket/issues/150) | test-permission-probe-do-not-keep | probe | CLOSED | Safe to keep closed. |
| [151](https://github.com/NotCherry/LFM3Pocket/issues/151) | [Voice] permission check — ignore | probe | CLOSED | Safe to keep closed. |
| [152](https://github.com/NotCherry/LFM3Pocket/issues/152) | [Voice] label-on-create probe | probe | CLOSED | Safe to keep closed. |

## Related open work (not epic child PRs)

This list is the 2026-11-18 snapshot. #204, #206, #207, and #208 have since merged. See the 2026-11-23 update.

Snapshot of PRs that were open on that date:

- [#204 Fix critical voice authorization and runtime lifecycle issues](https://github.com/NotCherry/LFM3Pocket/pull/204) (draft) — mentions #190/#191/#192/#193/#196/#200/#201.
- [#206 Reject voice confirmation when inference fails](https://github.com/NotCherry/LFM3Pocket/pull/206) — references #192.
- [#207 fix(voice): make system TTS cleanup session-aware](https://github.com/NotCherry/LFM3Pocket/pull/207) — references #203.
- [#208 fix(voice): clean abandoned voice-action recordings on startup](https://github.com/NotCherry/LFM3Pocket/pull/208) — references #197.

No open PRs were found targeting child issues #159–#166 at audit time.

## Owner follow-ups still required (bot 403)

1. Create labels: `voice`, `epic`, `phase-1` … `phase-6`, `phase-next`, `android`, `needs-device-test` (some `voice`/`phase-*` labels exist on issues; verify full set and milestones).
2. Create milestones named in the plan (Voice V1–V6, Voice next) and apply them to #167 and #153–#166.
3. Apply/normalize labels on #167 and #153–#166 as needed.
4. Record real arm64 device evidence for [#158](https://github.com/NotCherry/LFM3Pocket/issues/158) / [`docs/VOICE_LOOP_CHECKLIST.md`](../VOICE_LOOP_CHECKLIST.md), or explicitly track remaining NOT_RUN cases on the epic.

## What remains to close epic #167

The 2026-11-23 update above covers reminders (#159 / #219), calendar (#161 / #222), messages (#160 / #216), notification listing (#162 / #220), the assistant gesture (#163 / #218), the Vulkan spike note (#164 / #217), explicit compact (#165 / #221), local documents (#166 / #223), and the confirm/unload fixes in #204 (also #206). #207 and #208 have also merged.

What remains is evidence and housekeeping, not those child implementations:

- Produce arm64 on-device evidence for VL-01–VL-06 (currently unrecorded) and update the checklist and epic with outcomes.
- Apply owner-managed labels and milestones, and keep this index synchronized with issue and PR reality.

