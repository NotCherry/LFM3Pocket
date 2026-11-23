# ADR 0024 — Settings UI as section controllers behind a settings host

- Status: Accepted
- Date: 2026-11-23
- Related: [#264](https://github.com/NotCherry/LFM3Pocket/issues/264), [#285](https://github.com/NotCherry/LFM3Pocket/pull/285), [#286](https://github.com/NotCherry/LFM3Pocket/pull/286), [#292](https://github.com/NotCherry/LFM3Pocket/pull/292), [ADR 0002](0002-interface-size-density.md), [`DESIGN.md`](../DESIGN.md)

## Context

Settings, chat construction, and activity lifecycle grew up in one activity. Every settings area — appearance, images, inference, search, background, speech, and calendar — was built, painted, and saved there. A change in one area had to be reviewed against the rest, and feature work kept landing in the same class.

[#264](https://github.com/NotCherry/LFM3Pocket/issues/264) asks for a pure split. Behaviour and visuals stay as they are. Stable resource ids (`settings_*`, `chat_*`, `import_*`, `home_*`) stay as they are. The work lands one section at a time so it does not block feature changes. When the split is finished, the activity keeps lifecycle, activity results, and wiring. Migration progress is tracked in [#264](https://github.com/NotCherry/LFM3Pocket/issues/264), not in this record.

[#285](https://github.com/NotCherry/LFM3Pocket/pull/285) moved Appearance out as its own section. That section still reached the activity directly. [#286](https://github.com/NotCherry/LFM3Pocket/pull/286) introduced the settings section host: the activity implements that contract, and a section depends on it. Image moved in the same change, and Appearance was retargeted onto the host. Image kept its model and store on the section.

Screen recipes stay in [`DESIGN.md`](../DESIGN.md). This record is the structure those screens are built with.

## Decision

Settings UI is a set of section controllers behind one settings section host.

1. **A section owns its screen.** Each settings section builds its views, paints them, and persists its values. That work stays on the section.
2. **Sections talk to the settings section host.** The host stays minimal: only hooks shared by several sections belong on it — via `SettingsUiFactory` for UI factories, plus lifecycle and finishing state, the main handler, and settings-scroll helpers. One lifecycle hook recreates the activity after an interface-size change ([ADR 0002](0002-interface-size-density.md)). Section-specific stores, models, and callbacks go on the section’s constructor, not on the host ([#286](https://github.com/NotCherry/LFM3Pocket/pull/286) removed `imageSettings()`, `setImageSettings()`, and `imageSettingsStore()` from the host and passes them into `ImageSettingsSection`’s constructor). The activity implements the host. A section talks to that contract, not the activity class.
3. **The activity keeps lifecycle and wiring.** It keeps lifecycle, activity results, and the wiring that attaches sections. Chat shell construction is separated from settings construction as part of the same split ([#264](https://github.com/NotCherry/LFM3Pocket/issues/264)). Section paint and section persistence stay on the sections.
4. **Stable UI ids are a compatibility contract.** `settings_*`, `chat_*`, `import_*`, and `home_*` ids stay in place as views move (`home_*` via Home Compose test tags such as `home_new_chat`). Tests and the design system keep addressing the same ids.
5. **The move is one section per pull request, and each pull request is a pure refactor.** Which sections have moved and which remain is tracked in [#264](https://github.com/NotCherry/LFM3Pocket/issues/264).

## Consequences

- New settings behaviour is added on a section. A shared or activity-level need goes through the settings section host. A section-specific dependency is passed into the section.
- The host stays a narrow contract. It grows only when more than one section needs the same activity-level hook. Otherwise the section takes the dependency via its constructor, as Image does after [#286](https://github.com/NotCherry/LFM3Pocket/pull/286).
- Chat status (`chat_status`) lives on `ChatShellSection`; all activity updates go through `MainActivity.setChatStatus` (including turn visibility via `applyChatStatusDuringTurn`). Settings sections receive a `Consumer<CharSequence>` status sink wired to that method ([#292](https://github.com/NotCherry/LFM3Pocket/pull/292)).
- E2E and design-system selectors keep working across the split because the ids do not change. A move pull request leaves behaviour and visuals as they are.
- A settings change can be reviewed inside one section, against the host contract and the id contract.

## Alternatives considered

- **Keep growing the activity.** Rejected. [#264](https://github.com/NotCherry/LFM3Pocket/issues/264) exists because every feature was landing in the same class and reviews were colliding.
- **Sections hold the activity.** This is how Appearance first landed ([#285](https://github.com/NotCherry/LFM3Pocket/pull/285)). Rejected as the lasting shape: a section then depends on the concrete activity, and the next section inherits that coupling. [#286](https://github.com/NotCherry/LFM3Pocket/pull/286) replaced it with the host.
- **Put each section's model and store on the host.** Rejected. The host would grow a member for every section and become a second activity. [#286](https://github.com/NotCherry/LFM3Pocket/pull/286) kept the image model and store on the Image section.
- **One pull request for the whole split.** Rejected. [#264](https://github.com/NotCherry/LFM3Pocket/issues/264) asks for small pull requests, one section at a time, so the split does not block feature work. Each one stays a pure refactor.

## Amended (#292)

[#292](https://github.com/NotCherry/LFM3Pocket/pull/292) extracts the chat shell into `ChatShellSection` and closes the transitional `setChatStatus` hook on `MainActivitySettingsHost`.

- **Chat status ownership.** The `chat_status` line is built and updated inside `ChatShellSection`. `MainActivity` routes every status write through `setChatStatus` (and `applyChatStatusDuringTurn` for runtime chat updates that hide the line during a turn). Settings sections use a `Consumer<CharSequence> chatStatus` wired to `MainActivity.setChatStatus`, not the host interface.
- **Host contract.** `setChatStatus` is removed from `MainActivitySettingsHost`. Shared UI factories (`label`, `button`, `dp`, `getString`, `settingsActivity`) move to `SettingsUiFactory`, which the host extends. `ChatShellSection` depends on `SettingsUiFactory` only, not the full settings host.
