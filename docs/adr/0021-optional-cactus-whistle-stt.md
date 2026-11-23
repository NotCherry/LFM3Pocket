# ADR 0021 — Optional Cactus Whistle STT

- Status: Accepted
- Date: 2026-11-23
- Related: [#224](https://github.com/NotCherry/LFM3Pocket/pull/224), [#251](https://github.com/NotCherry/LFM3Pocket/pull/251), [#271](https://github.com/NotCherry/LFM3Pocket/pull/271), [ADR 0006](0006-voice-actions-loop.md), [ADR 0010](0010-tiny-voca-english-tts.md), [ADR 0022](0022-background-model-downloads.md), [`CACTUS_WHISTLE_STT.md`](../CACTUS_WHISTLE_STT.md)
- Amended: see [ADR 0022](0022-background-model-downloads.md) ([#261](https://github.com/NotCherry/LFM3Pocket/issues/261))

## Context

Hands-free commands default to one LFM3-Omni audio pass on the loaded model ([ADR 0006](0006-voice-actions-loop.md)). That pass stays the product default. It also spends a multimodal decode on the main weights for every utterance.

[Cactus Whistle](https://huggingface.co/Cactus-Compute/whistle) is a separate on-device speech-to-text model (`whistle.cact`, about 16.9 MB, Apache-2.0). Upstream advertises several languages. The voice loop already authorizes tools and confirms them before they run. Turning speech into text must not bypass either rule.

The app needs an opt-in input backend for that model. Weights and the Cactus native runtime are not part of the default install. [#224](https://github.com/NotCherry/LFM3Pocket/pull/224) added the backend, with the model supplied as a file the user selects. [#251](https://github.com/NotCherry/LFM3Pocket/pull/251) added an explicit in-app download of a pinned revision, on the same pattern as the tiny-voca pack ([ADR 0010](0010-tiny-voca-english-tts.md)). In-app download scheduling (UIDT, WorkManager, permissions, metered confirmation, cancel, free space) is recorded in [ADR 0022](0022-background-model-downloads.md).

## Decision

1. **Backend** — Cactus Whistle is an optional, opt-in speech-to-text input. LFM3-Omni audio stays the default. When Whistle is selected, the transcript enters the existing text pipeline. Tool authorization and confirmation stay as in [ADR 0006](0006-voice-actions-loop.md).
2. **Weights** — Not in the APK and not in the repository. The user obtains `whistle.cact` in one of two ways, both stored in app-private storage:
   - an explicit, user-initiated in-app download from a pinned `Cactus-Compute/whistle` revision on Hugging Face, kept only after SHA-256 verification;
   - a file the user selects.

   The app never downloads the model on its own. **Remove** clears the selection and deletes the in-app downloaded copy. A user-selected file stored under a name other than `whistle.cact` stays in app-private storage until app data is cleared. The current pin and checksum are recorded in [`CACTUS_WHISTLE_STT.md`](../CACTUS_WHISTLE_STT.md).
3. **Runtime** — Cactus runtime binaries are not committed and are not bundled by default. They are supplied as per-ABI build artifacts. A build without them still compiles and reports the backend unavailable.
4. **Privacy** — No audio or transcript is uploaded. There is no cloud speech-to-text fallback.
5. **Scope** — The initial product is English-only, even though upstream supports more languages. A wider set waits on a product decision and updated copy.

This extends the pinned-download pattern in [ADR 0010](0010-tiny-voca-english-tts.md): optional weights, a pinned upstream revision, SHA-256 before the file is kept, and app-private storage. Here the artifact is an input model, not a Speak-reply pack.

## Consequences

- Extra optional download (about 16.9 MB) and a second native inference stack, used only when the user selects Whistle.
- License: the Whistle model and the Cactus runtime are Apache-2.0. The app stays MIT.
- A revision bump requires updating the recorded pin and the checksum together. A mismatch is rejected and is not stored.
- Functional transcription depends on runtime availability per ABI. Without those artifacts the build still compiles, the backend reports unavailable, and the default Omni path is unchanged.

## Alternatives considered

- **Bundle the weights in the APK** — Rejected. The default install stays smaller, and the model stays an explicit optional download.
- **Unpinned or "latest" download** — Rejected. A moving upstream file would change bytes and behavior without a recorded pin and checksum.
- **Cloud speech-to-text** — Rejected. The product is offline-first; audio and transcripts stay on device.
- **Omni-only** — Rejected for this feature. An optional on-device speech-to-text path was requested. Omni audio stays the default.
