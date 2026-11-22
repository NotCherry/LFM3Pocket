# ADR 0012 — Two-tier emulator E2E gating: PR smoke vs deep/nightly

- Status: Accepted
- Date: 2026-11-22
- Related: [#252](https://github.com/NotCherry/LFM3Pocket/pull/252) (implementation), [#239](https://github.com/NotCherry/LFM3Pocket/issues/239)

## Context

Emulator end-to-end checks are the quality gate for native inference, chat UI, and persistence across process death. One connected run of the whole instrumentation inventory on every pull request was too slow for review, and the set the gate required could drift from the set that actually ran. Heavy, matrix, stress, native-deep, and accessibility-at-scale coverage still has to fail the build when it breaks.

## Decision

Hanami keeps two emulator E2E tiers. Both class contracts live in one gate script, `.github/scripts/assert-android-test-results.py`, which is the single source of truth. The emulator job takes its instrumentation class filter from that script (`--list-classes`), so the classes that execute and the classes the gate requires stay the same list.

Triggers (`.github/workflows/build-apk.yml`):

- **Pull requests** to `main` (except paths ignored below) → smoke tier.
- **Push to `main`** that touches paths outside `docs/**` and `**/*.md`, the nightly schedule (`17 3 * * *`), and **workflow dispatch** (tier choice, default `deep`) → deep tier.
- Pushes and pull requests that change only documentation under those `paths-ignore` rules do **not** start this workflow.

### PR smoke

When the emulator job runs for a pull request, it uses the smoke tier. The tier stays intentionally narrow and fast, and it always includes three proofs:

1. A real JNI/native inference proof on the emulator (`GenerationSmokeTest`).
2. App boot and the core chat UI flow (`ComposeHostE2eTest`).
3. A cross-process settings-restart persistence proof. This proof is a scripted phase of its own (`.github/scripts/verify-settings-restart.sh`), with its own result gate, and it stays on the pull-request path together with the connected proofs above.

### Deep / nightly

When the emulator job runs in deep tier, the connected contract is an explicit inventory of the full intended `androidTest` set (`DEEP_REQUIRED_CLASSES` in the gate script), including heavy, matrix, stress, native-deep, and accessibility-at-scale coverage. Harness and helper types stay outside that inventory. Deep includes the smoke connected proofs and also runs the broader scripted cross-process durability proofs (settings, history-restart, and history-journey via `.github/scripts/verify-*.sh`).

After connected tests and those scripts, every deep run also executes `scripts/stress-flaky-e2e-194.sh` from `.github/scripts/run-jni-smoke.sh` (issue #194 flake evidence). That script is **not** part of either class contract, but under `set -Eeuo pipefail` its failure fails the deep emulator job the same as a missing required class. A separate workflow (`.github/workflows/e2e-flake-194-stress.yml`) can dispatch the same stress script alone for triage.

### Enrolling new instrumentation classes

A class runs in CI and is required by a tier only after the gate script lists it in `SMOKE_REQUIRED_CLASSES` or `DEEP_REQUIRED_CLASSES`. Today the script does **not** define an exclusion list and does **not** compare `app/src/androidTest` against those contracts. `run-jni-smoke.sh` filters Gradle connected tests to the tier list, so a new `*Test` class that nobody adds to a contract is **not executed and not gated**—CI stays green with no signal that the class was forgotten. Reviewers must verify new instrumentation classes against the gate script in the same change.

**Follow-up (not implemented yet):** add `EXCLUDED_CLASSES` in the gate script (each entry with a short rationale) and a CI check (for example in `--self-test`) that every intended instrumentation class is in deep, smoke, or exclusions—tracked as a separate change/issue.

## Consequences

- Pull requests keep a fast emulator gate that still proves native inference, app boot plus core chat UI, and settings persistence across a process restart.
- Deep runs (push to `main` outside doc-only paths, nightly schedule, or deep manual dispatch) fail when any class in the deep inventory is missing, failing, or skipped, when the run recorded no tests, when scripted durability proofs fail, or when the #194 stress script fails.
- Authors who add an instrumentation class update the gate script (`SMOKE_REQUIRED_CLASSES` or `DEEP_REQUIRED_CLASSES`) in the same change until `EXCLUDED_CLASSES` and an inventory check exist.
- The #194 stress script repeats known flaky cases; it does not add classes to either contract but still gates deep tier success.
- A regression outside the smoke proofs can merge on a green pull request. The deep tier is what catches it.
- A **red deep or nightly** Android Stage 1 CI run on `main` is treated as a release-health blocker: the merge author or whoever landed the regressing commit fixes forward or reverts, and opens or updates an issue that links the failed workflow run until `main` is green again. No special on-call rotation is defined in-repo; the owner of the last non-doc push to `main` is the default responder.

## Alternatives considered

- **Full deep inventory on every pull request** — Rejected; emulator time dominated review latency before #252.
- **Sharding or an emulator matrix** — Rejected for now; operational cost and flaky-surface area without solving contract drift.
- **Path-based test selection** — Rejected; easy to miss cross-cutting JNI, Room, and Compose failures when only unrelated paths change.
- **Rely on class presence in `androidTest` without an explicit gate list** — Rejected; that was the drift problem #252 fixed by tying execution and assertion to one script.