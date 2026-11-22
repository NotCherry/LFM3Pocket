# ADR 0012 — Two-tier emulator E2E gating: PR smoke vs deep/nightly

## Status

Accepted (2026-11-22). Implemented in [#252](https://github.com/NotCherry/LFM3Pocket/pull/252), which closed [#239](https://github.com/NotCherry/LFM3Pocket/issues/239).

## Context

Emulator end-to-end checks are the quality gate for native inference, chat UI, and persistence across process death. One connected run of the whole instrumentation inventory on every pull request was too slow for review, and the set the gate required could drift from the set that actually ran. Heavy, matrix, stress, native-deep, and accessibility-at-scale coverage still has to fail the build when it breaks.

## Decision

Hanami keeps two emulator E2E tiers. Both class contracts live in one gate script, `.github/scripts/assert-android-test-results.py`, which is the single source of truth. The emulator job takes its instrumentation class filter from that script, so the classes that execute and the classes the gate requires stay the same list.

### PR smoke

When the emulator job runs for a pull request, it uses the smoke tier. The tier stays intentionally narrow and fast, and it always includes three proofs:

1. A real JNI/native inference proof on the emulator.
2. App boot and the core chat UI flow.
3. A cross-process settings-restart persistence proof. This proof is a scripted phase of its own, with its own result gate, and it stays on the pull-request path together with the connected proofs above.

### Deep / nightly

When the emulator job runs for a push to `main` or for the nightly schedule, it uses the deep tier. Manual workflow dispatch selects a tier and defaults to deep. The connected contract is an explicit inventory of the full intended `androidTest` set, including heavy, matrix, stress, native-deep, and accessibility-at-scale coverage. Harness and helper types stay outside that inventory. Deep includes the smoke proofs and also runs the broader scripted cross-process durability proofs (history survival across restart, and the history journey).

A new instrumentation class runs in CI, and is required by a tier, only after the gate script lists it in the deep contract or the smoke contract. Leaving it out of both contracts is an explicit exclusion: the class stays out of the run and out of the gate. The flake-triage stress script stays outside both class contracts. It still runs with the deep/nightly emulator pass, and it can be dispatched on its own for triage.

## Consequences

- Pull requests keep a fast emulator gate that still proves native inference, app boot plus core chat UI, and settings persistence across a process restart.
- Push to `main`, the nightly schedule, and a deep manual dispatch fail when any class in the explicit inventory is missing, failing, or skipped, or when the run recorded no tests.
- Authors who add an instrumentation class update the gate script in the same change, or record an explicit exclusion. Presence in the instrumentation source set alone does not enroll the class.
- The flake-triage stress script repeats known flaky cases for nightly and triage. It does not admit new classes into either contract.
- A regression outside the smoke proofs can merge on a green pull request. The deep tier is what catches it.
