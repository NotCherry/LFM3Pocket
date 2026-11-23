# ADR 0024 — Settings UI as section controllers behind a settings host

- Status: Accepted. Migration in progress per [#264](https://github.com/NotCherry/LFM3Pocket/issues/264). Appearance landed in [#285](https://github.com/NotCherry/LFM3Pocket/pull/285). The settings host and the Image section landed in [#286](https://github.com/NotCherry/LFM3Pocket/pull/286). Inference, Search, Background, Speech, Calendar, and separating the chat shell are still to move.
- Date: 2026-11-23
- Related: [#264](https://github.com/NotCherry/LFM3Pocket/issues/264), [#285](https://github.com/NotCherry/LFM3Pocket/pull/285), [#286](https://github.com/NotCherry/LFM3Pocket/pull/286), [`DESIGN.md`](../DESIGN.md)

## Context

Settings, chat construction, and activity lifecycle grew up in one activity. Every settings area — appearance, images, inference, search, background, speech, and calendar — was built, painted, and saved there. A change in one area had to be reviewed against the rest, and feature work kept landing in the same class.

[#264](https://github.com/NotCherry/LFM3Pocket/issues/264) asks for a pure split. Behaviour and visuals stay as they are. Stable resource ids (`settings_*`, `chat_*`, `import_*`, `home_*`) stay as they are. The work lands one section at a time so it does not block feature changes. When the split is finished, the activity keeps lifecycle, activity results, and wiring.

[#285](https://github.com/NotCherry/LFM3Pocket/pull/285) moved Appearance out as its own section. That section still reached the activity directly. [#286](https://github.com/NotCherry/LFM3Pocket/pull/286) introduced the settings host: the activity implements the host, and a section depends on that contract. Image moved in the same change, and Appearance was retargeted onto the host.

Screen recipes stay in [`DESIGN.md`](../DESIGN.md). This record is the structure those screens are built with.

## Decision

Settings UI is a set of section controllers behind one settings host.

1. **A section owns its screen.** Each settings section builds its views, paints them, and persists its values. That work stays on the section.
2. **Sections talk to the host.** The host is the contract for what a section may ask of the screen: context, shared chrome, and the few activity-level hooks a section needs. The activity implements the host. A section talks to that contract, not the activity class.
3. **The activity keeps lifecycle and wiring.** It keeps lifecycle, activity results, and the wiring that attaches sections. Chat shell construction is separated from settings construction as part of the same split. Section paint and section persistence stay on the sections.
4. **Stable UI ids are a compatibility contract.** `settings_*`, `chat_*`, `import_*`, and `home_*` ids stay in place as views move. Tests and the design system keep addressing the same ids.
5. **The move is one section per pull request, and each pull request is a pure refactor.** Appearance landed first ([#285](https://github.com/NotCherry/LFM3Pocket/pull/285)). The host, with Image on it, landed next ([#286](https://github.com/NotCherry/LFM3Pocket/pull/286)). Still to move: Inference, Search, Background, Speech and Calendar, and separating the chat shell.

## Consequences

- New settings behaviour is added on a section, through the host.
- Until the remaining sections and the chat shell move, the activity still owns that work. The host contract is the shape they move into.
- A section that needs something the host does not offer extends the host.
- E2E and design-system selectors keep working across the split because the ids do not change. A move pull request leaves behaviour and visuals as they are.
- A settings change can be reviewed inside one section, against the host contract and the id contract.

## Alternatives considered

- **Keep growing the activity.** Rejected. [#264](https://github.com/NotCherry/LFM3Pocket/issues/264) exists because every feature was landing in the same class and reviews were colliding.
- **Sections hold the activity.** This is how Appearance first landed ([#285](https://github.com/NotCherry/LFM3Pocket/pull/285)). Rejected as the lasting shape: a section then depends on the concrete activity, and the next section inherits that coupling. [#286](https://github.com/NotCherry/LFM3Pocket/pull/286) replaced it with the host.
- **One pull request for the whole split.** Rejected. [#264](https://github.com/NotCherry/LFM3Pocket/issues/264) asks for small pull requests, one section at a time, so the split does not block feature work. Each one stays a pure refactor.
