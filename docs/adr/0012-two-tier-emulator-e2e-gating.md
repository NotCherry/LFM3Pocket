# ADR 0012 — Two-tier emulator E2E gating: PR smoke vs deep/nightly

- Status: Accepted
- Date: 2026-11-22
- Related: [#252](https://github.com/NotCherry/LFM3Pocket/pull/252), [#239](https://github.com/NotCherry/LFM3Pocket/issues/239)
- Amended: 2026-11-23 ([#278](https://github.com/NotCherry/LFM3Pocket/pull/278) inventory check + EXCLUDED_CLASSES)
- Amended: 2026-11-23 — Exception to “no blanket retry” on emulator E2E: Stage 1 may re-start the emulator once (two starts total) only when attempt 1 fails before connected tests begin (pre-test infrastructure flake). Real tier test failures still fail the job; see [ADR 0023](0023-layered-test-timeouts.md) ([#283](https://github.com/NotCherry/LFM3Pocket/pull/283)).

## Context

Emulator end-to-end checks are the quality gate for native inference, chat UI, and persistence across process death. One connected run of the whole instrumentation inventory on every pull request was too slow for review, and the set the gate required could drift from the set that actually ran. Heavy, matrix, stress, native-deep, and accessibility-at-scale coverage still has to fail the build when it breaks.

## Decision

Hanami keeps two emulator E2E tiers. Both class contracts live in one gate script, `.github/scripts/assert-android-test-results.py`, which is the single source of truth. The emulator job takes its instrumentation class filter from that script, so the classes that execute and the classes the gate requires stay the same list.

Pull requests and pushes to `main` skip the emulator job when the change is only documentation or markdown. Any other pull request uses the smoke tier. Any other push to `main` uses the deep tier. The nightly schedule always uses deep. Manual workflow dispatch selects a tier and defaults to deep.

### PR smoke

The smoke tier stays intentionally narrow and fast, and it always includes three proofs:

1. A real JNI/native inference proof on the emulator.
2. App boot and the core chat UI flow.
3. A cross-process settings-restart persistence proof. This proof is a scripted phase of its own, with its own result gate, and it stays on the pull-request path together with the connected proofs above.

### Deep / nightly

The connected contract is an explicit inventory of the full intended `androidTest` set, including heavy, matrix, stress, native-deep, and accessibility-at-scale coverage. Harness and helper types stay outside that inventory. Deep includes the smoke proofs and also runs the broader scripted cross-process durability proofs (history survival across restart, and the history journey).

On every deep pass — a non-documentation push to `main`, the nightly schedule, and a manual deep dispatch — the flake-triage stress script runs after those proofs. It does not add classes to either contract. A failure fails the deep job, so the script gates that tier. The same script can be dispatched on its own for triage.

### Enrolling a new instrumentation class

Add the simple class name to the deep tier list in `.github/scripts/assert-android-test-results.py` (and to the smoke tier list when it should run on pull requests). Harness or helper types that live under `app/src/androidTest/` but are not JUnit test classes belong on the script’s exclusion list, each with a one-line justification. A type that carries `@Test` cannot be excluded.

The `unit` job runs `python3 .github/scripts/assert-android-test-results.py --check-inventory`, which scans `app/src/androidTest/` and fails when any instrumented test class is missing from those lists, when a listed class has no source file, when an exclusion has no justification, or when an exclusion would drop a real test class. Smoke classes must remain a subset of the deep list.

## Consequences

- Pull requests that run the emulator job keep a fast gate that still proves native inference, app boot plus core chat UI, and settings persistence across a process restart.
- A deep run fails when any class in the explicit inventory is missing, failing, or skipped, when the run recorded no tests, when a scripted durability proof fails, or when the flake-triage stress script fails.
- Authors who add an instrumentation class update the gate script in the same change. The cheap inventory check in the `unit` job reports classes that are missing from the deep or exclusion lists.
- A regression outside the smoke proofs can merge on a green pull request. A red deep or nightly run on `main` is a mainline blocker: the author of the change that landed it fixes forward or reverts, and opens or updates an issue that links the failed run until `main` is green again.

## Alternatives considered

- **Full inventory on every pull request.** This was the previous gate. Rejected: it was too slow for review ([#239](https://github.com/NotCherry/LFM3Pocket/issues/239)).
- **Sharding the same inventory across emulator jobs.** Rejected for this decision. The split is a contract of what must run on a pull request, not a way to put the full inventory back on that path.
- **Selecting tests from changed paths.** Rejected. A path filter does not keep a native-inference proof and a settings-persistence proof on every pull request that runs the emulator job, and it does not define the inventory the deep tier must keep.
