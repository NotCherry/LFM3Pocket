# ADR 0024 — MainActivity settings sections and minimal host

- Status: Accepted
- Date: 2026-11-23
- Related: [#264](https://github.com/NotCherry/LFM3Pocket/issues/264), [#285](https://github.com/NotCherry/LFM3Pocket/pull/285), [#286](https://github.com/NotCherry/LFM3Pocket/pull/286)

## Context

`MainActivity` owns the legacy View-based Settings scroll and chat shell. At ~2,900 lines it became the default landing zone for every feature (inference, search, speech, calendar, background mode). That raises merge conflict risk and makes the UI audit goal of a single Settings source harder ([#264](https://github.com/NotCherry/LFM3Pocket/issues/264)). Refactors must be behaviour-preserving: resource IDs, strings, and Stage 1 UiAutomator selectors stay fixed.

## Decision

Split Settings construction into **section classes** (one cohesive block per PR) and a **minimal `MainActivitySettingsHost` interface** that sections depend on instead of `MainActivity`.

**Host (shared across sections):** activity context, `dp`, `label`/`button`, `getString`, `isHostFinishing`, `settingsMainHandler`, interface-size recreate hook, chat status line, and `addSettingInput` for nudgable numeric rows.

**Constructor (section-specific):** stores, current-setting suppliers, save/configure callbacks, runtime handles (model handle, worker), and cross-section bundles (e.g. image settings read/persist during inference save). Sections do not add domain state to the host interface.

`MainActivity` implements the host and wires sections in `buildUi`. Lifecycle, `onActivityResult`, and activity-scoped flags remain on the activity.

## Consequences

- Section PRs stay small and independently mergeable; JVM + smoke E2E guard IDs on each merge.
- New sections must not widen the host with one-off dependencies; pass those through the constructor or a narrow nested interface (see `InferenceSettingsSection.BundledImageSettings`).
- `MainActivity` line count drops incrementally toward lifecycle + wiring only; chat shell extraction remains a later PR in the same issue.

## Alternatives considered

- **Inner classes inside `MainActivity`.** Rejected: no file-size win and harder to test in isolation.
- **Sections holding `MainActivity` directly.** Rejected after PR 1; replaced by the host interface in PR 2.
- **One mega-controller for all Settings.** Rejected: conflicts with the one-section-per-PR merge strategy and raises review risk.
