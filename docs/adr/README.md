# Architecture decision records

ADRs record durable product and architecture decisions: why a choice was made and what follows from it. Screen recipes stay in [`DESIGN.md`](../DESIGN.md). Class names, parameters, and line numbers stay in the code.

## Numbering

One file per decision: `NNNN-kebab-title.md`. Do not renumber an accepted record. A decision that is replaced keeps its number; the replacement is a new record that points at it.

Numbers **0003–0005** and **0007** were drafted on branches that never merged. This index fills them from the code on `main` and marks those records reconstructed retrospectively. **0008** and **0009** stay unused. See [Numbering gaps](#numbering-gaps).

## Template

```markdown
# ADR NNNN — Title

- Status: Proposed | Accepted | Accepted (reconstructed retrospectively) | Superseded by ADR-NNNN
- Date: YYYY-MM-DD
- Related: [#NNN](https://github.com/NotCherry/LFM3Pocket/pull/NNN), [ADR MMMM](MMMM-other.md), [`TOPIC.md`](../TOPIC.md)
- Amended: YYYY-MM-DD ([#NNN](https://github.com/NotCherry/LFM3Pocket/pull/NNN) short note)

## Context

## Decision

## Consequences

## Alternatives considered
```

Older files (for example [0002](0002-interface-size-density.md) and [0006](0006-voice-actions-loop.md)) use a `## Status` heading instead of the `- Status:` bullet. New records should prefer the bullet form above.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-arm64-dotprod-and-kv-residency.md) | arm64 DOTPROD kernels and text KV residency | Accepted |
| [0002](0002-interface-size-density.md) | Interface size via density wrap | Accepted |
| [0003](0003-turn-write-ahead.md) | Write the chat turn before inference | Accepted (reconstructed retrospectively) |
| [0004](0004-local-crash-log.md) | On-device crash log | Accepted (reconstructed retrospectively) |
| [0005](0005-prepare-vision-images.md) | Prepare vision images before the projector | Accepted (reconstructed retrospectively) |
| [0006](0006-voice-actions-loop.md) | Voice actions loop | Accepted |
| [0007](0007-voice-reminders-workmanager.md) | Local reminders via inexact WorkManager | Accepted (reconstructed retrospectively) |
| 0008 | — | Unused. See below. |
| 0009 | — | Unused. See below. |
| [0010](0010-tiny-voca-english-tts.md) | Optional English tiny-voca TTS | Accepted |
| [0011](0011-ngram-mod-prompt-lookup.md) | ngram-mod prompt lookup | Accepted |
| [0012](0012-two-tier-emulator-e2e-gating.md) | Two-tier emulator E2E gating | Accepted |
| [0013](0013-atlas-color-tokens-ci-hex-guard.md) | Atlas color tokens and the CI hex guard | Accepted |
| [0014](0014-calendar-tools-picked-calendar.md) | Calendar tools and the picked calendar | Accepted (reconstructed retrospectively) |
| [0015](0015-loopback-api-local-only.md) | Loopback API is local, bounded, and cannot approve | Accepted (reconstructed retrospectively) |
| [0016](0016-runtime-policy-owner-thread.md) | Runtime policy mutations stay on the owner thread | Accepted (reconstructed retrospectively) |
| [0017](0017-message-tools-open-composer.md) | Message tools open a composer; Hanami does not send | Accepted (reconstructed retrospectively) |
| [0018](0018-notification-list-opt-in.md) | Notification listing is opt-in, allowlisted, and untrusted | Accepted (reconstructed retrospectively) |
| [0019](0019-explicit-conversation-compact.md) | Conversation compact is explicit; the transcript stays | Accepted (reconstructed retrospectively) |
| [0020](0020-untrusted-local-documents.md) | Attached documents are untrusted and cannot propose tools | Accepted (reconstructed retrospectively) |
| [0021](0021-optional-cactus-whistle-stt.md) | Optional Cactus Whistle STT | Accepted |
| [0022](0022-background-model-downloads.md) | Background model downloads (UIDT / WorkManager) | Accepted |

## Numbering gaps

Parallel work in November 2026 claimed numbers on branches that did not all land. Accepted records were not renumbered to close the holes.

| Number | What happened |
| --- | --- |
| 0003, 0004 | Drafted on `cursor/adr-turn-persistence-86f8` (`241d004`, never merged) for write-ahead turns and the crash log. The behavior landed in [#170](https://github.com/NotCherry/LFM3Pocket/pull/170). [0003](0003-turn-write-ahead.md) and [0004](0004-local-crash-log.md) record that behavior as it is on `main`, including later durability fixes. |
| 0005 | Vision preparation was drafted as ADR 0001, then 0002, then 0005 on `cursor/docs-adr-vision-images-7a69` (`73533f2`). That branch is not an ancestor of `main` (a merge onto it was reverted). The behavior landed in [#169](https://github.com/NotCherry/LFM3Pocket/pull/169). [0005](0005-prepare-vision-images.md) records it. |
| 0007 | Drafted on `cursor/voice-v2-reminder-e0c8` (`1f71946`, never merged). A later commit on that branch preferred exact alarms. `main` has the earlier decision: inexact WorkManager, merged in [#219](https://github.com/NotCherry/LFM3Pocket/pull/219). [0007](0007-voice-reminders-workmanager.md) follows `main`, not the unmerged exact-alarm revision. |
| 0008 | Drafted as ADR 0001 (`e7f573f`) and renumbered to 0008 on `cursor/self-hosted-runner-fallback-328e` (`aba364c`) for a cherry-tree self-hosted CI fallback. That branch never merged. Workflows on `main` run on `ubuntu-latest` only. The number stays unused so a future self-hosted decision can take a new number instead of reviving an unmerged draft. |
| 0009 | Never written. No commit adds a file or mentions an ADR 0009. [0010](0010-tiny-voca-english-tts.md) and [0011](0011-ngram-mod-prompt-lookup.md) merged while 0003–0008 were still claimed off `main`, so the sequence jumped. |

One decision from the same period already has its own topic doc, and this index does not copy it into a new number:

- Vulkan offload stays opt-in, with CPU fallback: [`VOICE_GPU_NPU_SPIKE_164.md`](../VOICE_GPU_NPU_SPIKE_164.md) ([#217](https://github.com/NotCherry/LFM3Pocket/pull/217)).

