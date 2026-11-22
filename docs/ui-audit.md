# Hanami UI usability / styling audit

Phase 1 audit of production surfaces under `app/src/main` against [`DESIGN.md`](DESIGN.md) and Atlas / Stage 1 contracts. Findings are ranked by user impact. Line numbers refer to `origin/main` at audit time unless noted.

**Method:** Code walk of every screen/flow (Home, Chat, Settings View host, Compose Settings recipe, History, About, Camera, tool consent, empty/loading/error) plus emulator screenshots (`docs/evidence/ui-audit/`, Pixel 6 API 35, phone release APK). Live device visual for U16 remains pending separately.

**Verdict:** Production Settings is a single giant `ScrollView` in `MainActivity` (View), not `SettingsScreen.kt`. Compose Atlas tokens exist, but View chrome and several Compose screens still hardcode English, spacing, and a soft-chalk color outside `AtlasColors`. New-user path is Home → Open chat → must import a GGUF from Settings; there is no first-run onboarding.

---

## Emulator evidence (selected)

| Shot | Surface | What it shows |
| --- | --- | --- |
| [`01-home.png`](evidence/ui-audit/01-home.png) | Home | Brand + Open chat; **No model loaded** footer with no CTA; New chat absent on this build’s Home |
| [`02-chat-empty.png`](evidence/ui-audit/02-chat-empty.png) | Chat empty | Mode duplicated (header + composer); model warning quiet; Clear unlabeled in strings; suggestion chips English |
| [`03-chat-overflow.png`](evidence/ui-audit/03-chat-overflow.png) | Chat overflow | Settings / Web search only (hardcoded EN) |
| [`04-settings-top.png`](evidence/ui-audit/04-settings-top.png) | Settings top | Model import row wrapping; Load/Unload/Settings strip while already in Settings |
| [`05-settings-mid.png`](evidence/ui-audit/05-settings-mid.png) | Settings mid | SEARXNG jargon; low-contrast endpoint placeholder; dense mega-scroll |
| [`10-history-empty.png`](evidence/ui-audit/10-history-empty.png) | History | English empty copy; Home / New chat chrome |
| [`11-about.png`](evidence/ui-audit/11-about.png) | About | Fully hardcoded English About |

Also useful: existing [`docs/evidence/interface-size/`](evidence/interface-size/) (PR #175 compact density).

---

## Ranked findings

### P0 — blocks first-run or core comprehension

| ID | Finding | User impact | Location | Fix direction |
| --- | --- | --- | --- | --- |
| **F01** | **No first-run onboarding.** Launcher is `ComposeHostActivity` Home; Open chat / New chat jump into `MainActivity` with status `"No model loaded. Select an LFM3 GGUF file."` Import / Load live only inside Settings scroll. | New users land in an empty chat with no guided import CTA, download link, or “what is a GGUF?” explanation. | `AndroidManifest.xml`; `ComposeHostActivity.kt` Home; `MainActivity.java` model-empty status | Phase 3: first-run card or Home empty-model CTA that deep-links Settings → Model import; keep E2E IDs. |
| **F02** | **Model import/load discoverability.** Model pickers sit at the top of the Settings mega-scroll; chat top bar has New chat + gear only. Home shows `"No model loaded"` as muted footer text with no action. | Users who open chat first cannot import without finding Settings; model flow is not on the chat viewport. | Home footer; chat empty; Settings model row | Phase 3: promote Import/Load when no model; optional chat empty-state primary CTA. |
| **F03** | **Hardcoded English across primary surfaces (i18n broken for PL).** History, About, Chat empty/composer/tools, Camera, Benchmark, overflow menu, and many `status.setText(...)` paths bypass `strings.xml`. | Polish locale still shows English chrome and errors; DESIGN §11 requires EN+PL for user-visible chrome. | See **Hardcoded string inventory** | Phase 3: move literals to `values` / `values-pl`; Phase 2 string baseline lint. |

### P1 — high friction / accessibility / dual-source drift

| ID | Finding | User impact | Location | Fix direction |
| --- | --- | --- | --- | --- |
| **F04** | **Production Settings = giant `MainActivity` scroll; `SettingsScreen.kt` is recipe/test-only.** Comment admits production host is MainActivity; Compose screen used in `ComposeTextReadableTest` only. | Dual Settings UX: recipe misses Speech, Images, Background, Tools, Notification, Calendar, Crash log, Prompt lookup, Cactus/Whistle. Drift will keep growing. | `SettingsScreen.kt`; `MainActivity.java` configuration builder | Phase 2: document single source of truth; Phase 3: delete/stub recipe or share a section model. |
| **F05** | **Settings section order / burial (current main).** Order: Model → Appearance → Inference (+ Prompt lookup) → Benchmark → SEARXNG → Images → Background → Tools → Notification → Calendar → Speech (Whistle + tiny-voca) → Default assistant → Crash log. | Appearance is early (good). Speech / Images / Crash log require long scroll; Crash log is last. Power-user jargon sits before Speech. | `MainActivity.java` configuration | Phase 3: Everyday vs Advanced grouping; optional sticky section index. **Avoid churn in Speech while #251 lands.** |
| **F06** | **Touch targets under 48dp (View).** DESIGN §11 / `InterfaceSize.MIN_TOUCH_DP=48`, but many Settings/media rows use `dp(44)` or `dp(46)` via `StillwaterReadable` without `minTouchPx`. Compact interface scale shrinks those further. | Harder taps on Save search, media row, idle presets, Whistle/tiny-voca/crash buttons, stepper ±. | `MainActivity.java`; `StillwaterReadable.java`; `InterfaceSize.java` | Phase 2: lint interactive mins; Phase 3: raise floors; keep visual chrome dense (#175). |
| **F07** | **Clear vs New chat confusion.** Top bar **New chat** starts a new conversation ID. Composer **Clear** empties transcript without new ID — matches DESIGN §6.11, but Clear is unlabeled in strings, no `testTag`, English-only `"Clear"`. | Users may wipe a thread thinking they started a new one (or vice versa); PL users see English Clear. | `ChatPanel.kt`; DESIGN §6.11 | Phase 3: `clear_conversation` string + PL; rename to “Clear transcript”; add `chat_clear` tag; optional confirm. |
| **F08** | **Confusing product jargon labels.** `SEARXNG`, `Prompt lookup decode (ngram-mod)`, `Cactus Whistle STT`, `Whistle model (.cact)`, `tiny-voca` ship as primary labels. Polish keeps acronyms/brand tokens. | Non-experts cannot tell what to enable. | `strings.xml` / `values-pl/strings.xml` | Phase 3: plain-language titles + mono subtitle; PL glosses. Defer Whistle copy churn until after #251. |
| **F09** | **`settingsInlineError` only visible inside Settings.** Chat-side failures mostly go to `status` / `ChatLiveStatus`, many hardcoded English. | Invalid Save while Settings closed is invisible; chat status line is easy to miss and untranslated. | `MainActivity.java` save paths | Phase 3: sticky Save-row errors + localize status strings. |
| **F10** | **DESIGN corner-radius contradiction + hardcoded `RoundedCornerShape(0.dp)`.** §4 says 0–2dp for tool panels; §6.4 says tool panel **6–8dp**. Code uses `AtlasShapes` 0–2dp and many call sites force `0.dp`. | Implementers cannot know which radius is correct. | `DESIGN.md`; `AtlasShapes.kt`; `ChatPanel.kt`; `SettingsScreen.kt` | Phase 2: resolve DESIGN (prefer §4 + AtlasShapes); Phase 3: use `MaterialTheme.shapes.*` only. |
| **F11** | **Dual soft-chalk palette.** `StillwaterUi.SOFT` / `still_soft` `#C9C2B4` and Compose `ColorSoft` / Home hex are **not** in `AtlasColors`. | Quiet buttons and user-turn body diverge from token table. | `StillwaterUi.java`; `AtlasColors.kt`; `ChatTurnList.kt`; `ComposeHostActivity.kt`; `colors.xml` | Phase 2: add `inkSoft` + sync test. |
| **F23** | **Mode / status duplicated; Send/Stop low affordance.** Chat shows `Mode · Local / offline` in the title stack **and** inside the composer; Stop/Send read as weak text on charcoal (screenshot `02-chat-empty.png`). | Cluttered first chat viewport; primary actions hard to spot. | `ChatPanel.kt` / MainActivity chrome | Phase 3: single mode line; strengthen Send (accent) / Stop contrast per DESIGN §6.5. |
| **F24** | **Settings ModelManager “Settings” control while already in Settings.** Load / Unload / Settings strip appears at top of Settings host (screenshot `04-settings-top.png`). | Confusing “Settings” affordance that re-focuses a field instead of navigating. | `ModelManagerPanel.kt` | Phase 3: hide or relabel when host is already Settings. |

### P2 — consistency, polish, tooling

| ID | Finding | User impact | Location | Fix direction |
| --- | --- | --- | --- | --- |
| **F12** | **Literal spacing instead of `AtlasSpacing`.** History `16.dp` vs token `screen=12.dp`; About `24.dp`; turn padding hardcodes `12.dp`/`7.dp`. | Uneven rhythm; harder density scaling. | History / Home / ChatTurnList | Phase 2 spacing lint; Phase 3 replace literals. |
| **F13** | **Empty / loading feedback gaps.** Empty chat English; live status spinner keyed off English prefixes. | PL empty state English; some statuses skip progress UI. | `ChatTurnList.kt`; `ChatLiveStatus.kt`; `ModelManagerPanel.kt` | Phase 3: string resources; enum-driven live status. |
| **F14** | **Content descriptions gaps.** Overflow `"⋮"` and Camera `"Capture"` weak/hardcoded; voice CD nulled when Omni voice off. | TalkBack weaker on chrome. | MainActivity; CameraCaptureActivity; ChatPanel | Phase 3: string CDs. |
| **F15** | **`docs/theme/` missing.** Tokens live in DESIGN + `ui/theme/*` only. | Agents looking for theme package miss the map. | `docs/` | Phase 2: `docs/theme/README.md` pointer. |
| **F16** | **No XML layouts / no `dimens.xml`.** Programmatic View + Compose; `colors.xml` exists. | OK architecturally; CI layout lint won’t catch View mins. | `res/` | Prefer Kotlin tokens + script guard. |
| **F17** | **No token/string CI guard.** Stage 1 `unit` job already runs Python gates before Gradle. | Easy to add a fast check. | `.github/workflows/build-apk.yml`; `.github/scripts/` | Phase 2: `check-ui-tokens.py`. |
| **F18** | **About + Home English / soft chalk.** About fully English; Home soft chalk hex. | PL About unusable. | `ComposeHostActivity.kt` | Phase 3 + Phase 2 `inkSoft`. |
| **F19** | **Benchmark section English-only.** | PL Settings show English mid-scroll. | `MainActivity.java`; `SettingsScreen.kt` | Phase 3 strings; Advanced grouping. |
| **F20** | **Overflow “Web search” hardcoded.** | PL overflow English. | `MainActivity.java` | Phase 3 localize. |
| **F21** | **Tool panel / composer strings hardcoded** despite existing `R.string.approve` / `reject` / `android_action`. | PL chat chrome English. | `ChatPanel.kt` | Phase 3 wire resources. |
| **F22** | **History screen English-only shell.** | Entire History UI English in PL locale. | `HistoryActivity.kt` | Phase 3 full string extraction. |
| **F25** | **SEARXNG endpoint placeholder contrast.** Placeholder URL nearly invisible on charcoal (screenshot `05-settings-mid.png`). | Hard to discover/edit search endpoint. | Settings search field styling | Phase 3: use `inkDisabled` / stronger field chrome. |

---

## Hardcoded string inventory (requested surfaces)

### `HistoryActivity.kt`
- Error / empty / chrome: `"Could not read saved conversations"`, `"Home"` / `"Back"`, `"History"`, `"No saved conversations yet."`, `"Delete all history"`, `"Delete"`, retention notice, `"Android action"`, speech transcript labels, delete dialogs (`"Cancel"`, …).

### `ComposeHostActivity` About / Home
- About body + `"Back to home"`; Home `"ON DEVICE"`, tagline, `"No model loaded"` / `"· on device"`.

### `ChatPanel.kt`
- `"Android action"`, `"Reject"`, `"Approve"`, attachment captions, `"Write a message…"`, `"Clear"`, idle mode lines (also duplicated in resources).

### `CameraCaptureActivity.java`
- Access / status / `"Capture"` CD / `"Cancel"` / `"Camera"` chrome.

### Benchmark (`MainActivity` Settings)
- `"BENCHMARK"`, blurb, `"Run speed test"`, `"Copy results"`, related status copy.

### `SettingsScreen.kt` (recipe)
- Benchmark blurb, `"HTTPS search endpoint"`, other EN labels already present as resources elsewhere.

---

## Settings navigation map (production)

| Entry | Behavior |
| --- | --- |
| Home **Settings** | `MainActivity` + `EXTRA_OPEN_SETTINGS` |
| Chat gear (`settings_open`) | `setSettingsOpen(true)` |
| Chat overflow → Settings | same |
| Overflow → Web search | opens Settings + focuses SearXNG field |
| ModelManager **Settings** button | opens Settings + focuses context field (confusing when already in Settings — F24) |
| Back to chat | hides settings host, shows chat shell |

Production UI is one vertical scroll (`R.id.model_configuration_scroll`) with sticky Save / Save search. Not a multi-pane or Compose `SettingsScreen`.

**Interface size (#175):** Compact 85% / Default 100% / Large 115% + 80–130% slider under Appearance. Applied via `InterfaceSize.wrap` (`densityDpi`). Phase 2/3 must keep presets working.

---

## `res/` and CI notes

| Item | Status |
| --- | --- |
| `res/layout/` | **Absent** — programmatic View + Compose |
| `dimens.xml` | **Absent** |
| `colors.xml` | Present (`still_*` including `still_soft`) |
| `themes.xml` / night | Present |
| `docs/theme/` | **Absent** — use `docs/DESIGN.md` + `ui/theme/` |
| Workflows | `build-apk.yml` (unit + emulator E2E + APK), `release.yml`, `e2e-flake-194-stress.yml` |
| Token-lint hook | Add step in `build-apk.yml` `unit` job beside `check-acceptance-matrix.py` |

---

## Phase split recommendations

### Phase 2 — tokens / guard (CI + docs, minimal UX change)
1. Resolve DESIGN §4 vs §6.4 radius; add `docs/theme/README.md` pointer.
2. Add `AtlasColors.inkSoft` (= `#C9C2B4`); sync `StillwaterUi.SOFT` / `still_soft`; JVM equality test.
3. Add `.github/scripts/check-ui-tokens.py`: forbid raw hex / `Color(0x…)` outside theme + `StillwaterUi` / `colors.xml`; optional spacing allowlist; **baseline** for existing string literals so CI stays green until Phase 3 extracts them.
4. Wire script into `build-apk.yml` `unit` job (**not** docs-only paths-ignore — script + workflow change must run).
5. Annotate `SettingsScreen` as non-production; checklist so new sections land in `MainActivity` (or shared model).

### Phase 3 — usability fix PRs (suggested order; keep reviewable)
1. **P3a — i18n extraction:** History, About, ChatPanel, Camera, Benchmark, Clear, overflow, empty chat (F03/F18/F19/F21/F22).
2. **P3b — first-run / model CTA:** Home + empty chat import path (F01/F02).
3. **P3c — Settings IA + labels:** Everyday vs Advanced; plain-language SEARXNG / prompt lookup; defer Whistle/Speech copy until after #251 (F05/F08).
4. **P3d — Clear vs New chat + errors + chat chrome:** rename Clear, `chat_clear`, mode de-dupe, Send/Stop contrast, ModelManager Settings label (F07/F09/F23/F24).
5. **P3e — touch + spacing:** `minTouchPx` floors; replace literal spacing with `AtlasSpacing` (F06/F12).
6. **P3f — Settings single source:** migrate or delete Compose recipe drift (F04).

Keep Stage 1 resource IDs (`settings_*`, `chat_*`, `import_*`, `home_*`) stable per DESIGN anti-pattern #8. Rebase each PR on `main`. Avoid Speech / Whistle and E2E-trimming areas while #251 / #242 / #194 land.

---

## Out of scope / observed OK

- Appearance is **not** buried on current main (near top after Model) — burial risk is Speech / Crash / Calendar instead.
- Media Photo/Camera/Voice are labeled buttons in `MainActivity`, not icon-only Compose composer icons.
- `InterfaceSize.minTouchPx` is correctly used on chat top-bar New chat / gear / overflow mins and interface SeekBar.
- Polish coverage for shared `strings.xml` keys is generally careful; gaps are mostly **unextracted** Kotlin/Java literals and kept English jargon inside PL strings.
- Compact default density from #175 must remain the default; Interface size presets must keep working.
