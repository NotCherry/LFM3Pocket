# Hanami — Atlas design system

Authoritative visual and interaction guide for the **Atlas** UI epic (tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) / U00). Implementers of `PocketTheme` (#120 / U03) and screen issues (#126–#131) must follow this document.

The chosen product direction is **Stillwater**: warm-charcoal field, chalk ink, a single candle accent, serif display type, open text blocks, and a left **Reasoning** rail (collapsed by default, with a one-line preview). It does **not** replace Stage 1 AT-01..AT-09 gates; UI acceptance is tracked as UT-01..UT-08 in [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md).

---

## 1. Brand and composition rules

| Rule | Requirement |
| --- | --- |
| Brand first | On Home and chat chrome, **Hanami** is the hero wordmark. Liquid AI LFM3 names the models the app runs, not the product. |
| One composition | First viewport reads as one composition, not a dashboard of cards. |
| Hero budget (Home) | Brand + one headline + one short supporting sentence + one CTA group. No stats strips, schedule chips, or promo clusters. |
| Chat hero | Conversation owns the primary viewport. Model / search / settings are **out** of the first chat viewport (see U10). |
| No hero overlays | No floating badges, promo stickers, or info chips on top of hero media. |
| Cards | Default: **no cards**. Cards only when they wrap a discrete user interaction (e.g. tool Approve/Reject). If removing border/background does not hurt understanding, do not use a card. |
| One job per section | Each section: one purpose, one headline, usually one short supporting line. |

---

## 2. Color tokens

Use these names in code (`PocketTheme` / `AtlasColors`). Hex values are normative.

| Token | Hex | Role |
| --- | --- | --- |
| `surface` | `#0E0E0C` | App background. Full-bleed. |
| `surfaceRaised` | `#161614` | Slightly lifted panels (tool panel fill, settings groups). |
| `surfaceOverlay` | `#1C1C18` | Modals, consent sheet, overflow menus. |
| `surfaceGlow` | `#1C1A16` | Warm Home radial-gradient highlight (warmer than `surfaceOverlay`). |
| `ink` | `#F3EFE6` | Primary chalk text (body, headlines). |
| `inkSoft` | `#C9C2B4` | Quiet chalk for secondary body (user turns, soft Home copy). Between `ink` and `inkMuted`. |
| `inkMuted` | `#8F897C` | Secondary text, timestamps, hints. |
| `inkDisabled` | `#A39E92` | Disabled controls and placeholders. Light enough for WCAG AA on `surface`. |
| `accent` | `#E6C27A` | Candle: links, primary CTA fill, source links, stream cursor. |
| `accentMuted` | `#8C7340` | Pressed accent, subtle selection. |
| `border` | `#2C2B28` | Hairline borders for tool panels, composer, inputs. |
| `borderStrong` | `#4A473F` | Emphasized borders. |
| `danger` | `#E08A78` | Destructive / Reject. |
| `ok` | `#9DBEA4` | Success / approved tool result. |
| `warn` | `#C4A35A` | Caution / elevated tool risk. |
| `codeBg` | `#0A0A09` | Fenced code / mono blocks. |
| `cursor` | `#E6C27A` | Streaming block cursor `▌`. |
| `candleInk` | `#1C1508` | Text on candle fills. |

**Contrast:** Body text (`ink` on `surface`) must remain clearly readable outdoors-ish; do not drop body below chalk on charcoal. Accent is for affordances, not large filled backgrounds behind paragraphs.

**Material3 mapping (U03):**

| M3 role | Atlas token |
| --- | --- |
| `background` / `surface` | `surface` |
| `surfaceVariant` | `surfaceRaised` |
| `onBackground` / `onSurface` | `ink` |
| `onSurfaceVariant` | `inkMuted` |
| `primary` | `accent` |
| `onPrimary` | `candleInk` (dark ink on candle fill) |
| `outline` | `border` |
| `error` | `danger` |

Atlas epic is **dark-only**; no light ColorScheme required.

---

## 3. Typography

Prefer a purposeful pairing already shippable on Android without new font APK bloat unless the project already vendors fonts:

| Role | Spec | Use |
| --- | --- | --- |
| `display` | Serif, regular, ~56–64sp, tight tracking | Home brand / hero product name |
| `headline` | Serif, regular, ~24–28sp | Screen titles (Settings, History) |
| `title` | Serif, regular, ~16–18sp | Section titles, turn role labels if shown |
| `body` | Sans, regular, **~14sp**, line height ~1.4 | Assistant and user message bodies (compact default) |
| `bodySmall` | Sans, regular, ~12sp | Captions, timestamps, rail collapsed hint |
| `label` | Sans, semi-bold, ~10–12sp, slight letter-spacing | Pills, buttons, settings row labels |
| `mono` | Monospace (e.g. `FontFamily.Monospace`), ~12sp | Tool names, JSON args, paths, fenced code, risk codes |

**Rules**
- User vs assistant: same `body` size; distinguish by subtle role label or left gutter — **not** by large colored bubbles.
- Markdown headings map into `title` / `headline` scale; do not introduce a third decorative display face in chat.
- Avoid default “AI purple” or Inter-as-brand; system sans is acceptable if roles above are consistent.
- Defaults are intentionally dense (chat-assistant density). Users can raise or lower size via **Settings → Appearance → Interface size**.

---

## 4. Spacing and layout scale

Base unit **4dp**. Prefer this compact scale (`AtlasSpacing`):

| Token | dp |
| --- | --- |
| `space1` | 4 |
| `space2` | 8 |
| `space3` | 10 |
| `space4` | 12 |
| `space5` | 16 |
| `space6` | 20 |
| `space8` | 28 |
| `space10` | 36 |
| `space12` | 48 |

**Layout**
- Screen horizontal padding: ~12dp phone (`AtlasSpacing.screen`); keep content readable, not edge-glued.
- Turn vertical padding: ~7dp; tighter than the first Atlas pass.
- Composer inner padding: ~8dp; smaller top bar / chrome icons (~36dp visual) with a **48dp** touch floor.
- Reasoning rail width (expanded): ~36–42% of chat width on phone landscape / large; on narrow portrait, rail becomes a top-of-turn collapsible strip still **leading** (start side) aligned.
- Corner radius: **0–2dp** for inputs, buttons, and tool panels. Avoid pill chrome except tiny status dots.

### 4.1 Interface size (Appearance)

Persisted multiplier **80%–130%** (`InterfaceSize`, SharedPreferences key `interface_scale`). Presets: **Compact** 85%, **Default** 100%, **Large** 115%. Applied app-wide by scaling `densityDpi` in `attachBaseContext` so typography **and** spacing move together **on top of** the system font scale. Compose keeps `minimumInteractiveComponentSize` at a system **48dp** finger target so compact visuals do not shrink touch targets.

---

## 5. Elevation and borders

Atlas prefers **borders over shadows**.

- Default elevation: none (flat charcoal).
- Optional 1dp `border` around composer, tool panel, settings group.
- No multi-layer drop shadows, no glow, no neon accent bloom.
- Dividers: 1dp `border`, full bleed within content column — sparse, not broadsheet hairline grids.

---

## 6. Component recipes

### 6.1 Home hero (`ComposeHostActivity` / U09)

- Full-bleed `surface`.
- Product name at display scale (hero).
- One short supporting sentence in `inkMuted`.
- CTA group: primary **Open chat** (resume the last live session; accent filled or strong outline), secondary text buttons **New chat** (under **Open chat**), **History**, **Settings**, **About**.
- No cards, no stat strip, no inset hero image collage.
- Preserve E2E: open-chat control (`open_chat`), history open control (`history_open` / existing host tags), and the Home new-chat control (`home_new_chat`).

### 6.2 Chat turn (`ChatTurnList` / U08)

- Open text block on `surface` — **not** a Material message bubble card.
- Optional quiet role marker (`YOU` / `Hanami`) in `label` + `inkMuted`. The flat automator transcript still greps `LFM3` (see `UI_E2E_CONTRACT.md`).
- Body via `MarkdownBody` (`ink`).
- Sources: steel-accent text links or compact link row under the answer — not chips stuck on a hero.
- Media: preview chip / thumbnail row under the turn or above composer (see 6.6) — not only `[photo attached]` plain text when a bitmap is available.
- Streaming: append chalk block cursor `▌` to the active assistant body.

### 6.3 Reasoning rail (U08)

- **Placement:** start (left in LTR) of the assistant turn.
- **Default:** collapsed. Collapsed control shows `Reasoning` (or equivalent) in `bodySmall` / `inkMuted` with chevron.
- **Expanded:** mono or `bodySmall` text of cleaned reasoning; `surfaceRaised` + `border`; no purple glow.
- Expanding must not steal focus from stream cursor in the answer body.
- Reasoning content is **never** concatenated into the answer body or into the flat E2E answer lines as raw tags (see sanitizer U06).

### 6.4 Tool panel (U11)

- Bordered panel: `surfaceRaised` fill, `border` stroke, radius **0–2dp** (same as §4 / `AtlasShapes`; nearly square).
- Header: tool name in `mono` + optional risk tint (`warn` / `danger` / `ok`).
- Body: arguments / details in `mono`, selectable where useful.
- Actions: **Approve** / **Reject** as clear buttons; keep stable IDs `tool_approve`, `tool_reject`, `tool_details`, `tool_status` (add `tool_risk`, `tool_result` per U11).
- This is one of the few justified “card-like” containers because it wraps a consent interaction.

### Voice actions (V1)

Hands-free voice rides the same model residency and tool consent as shade Approve/Reject. Push-to-talk and notification **Talk** default to one LFM3-Omni audio pass; tool JSON still arrives only as a model proposal. Settings may instead select optional Cactus Whistle STT, in which case command and confirm transcription run through the text pipeline ([#224](https://github.com/NotCherry/LFM3Pocket/pull/224), [`CACTUS_WHISTLE_STT.md`](CACTUS_WHISTLE_STT.md), [ADR 0021](adr/0021-optional-cactus-whistle-stt.md)). Spoken confirm uses the exact action read-back plus a closed tak/nie (yes/no) lexicon. A failed or cancelled confirm pass rejects, even if the partial text contains an approval word. Mic capture is a foreground type only during listen windows, and unload cancels listen and confirmation before the model goes away. Later tools — local reminders ([ADR 0007](adr/0007-voice-reminders-workmanager.md)) and calendar read/write ([ADR 0014](adr/0014-calendar-tools-picked-calendar.md)) — use that same consent. Details: [ADR 0006](adr/0006-voice-actions-loop.md).

### 6.5 Composer (U11)

- Anchored bottom bar on `surfaceRaised` with top `border`.
- Multiline field: chalk caret, `ink` text, placeholder `inkDisabled`.
- Send uses accent; Stop remains obvious during generation (`chat_send` / `chat_stop`).
- Voice / camera / photo affordances stay reachable without reclaiming ⅓ of the viewport for config.

### 6.6 Media chip

- Small preview thumbnail + remove control (`image_preview`, `image_remove`, etc.).
- Border `border`; no floating sticker overlay on the transcript hero.
- A photo sent with a turn is copied completely into private storage before inference, so Retry after process death can use it. An incomplete copy is discarded and does not replace a previous file ([ADR 0003](adr/0003-turn-write-ahead.md)).

### 6.7 Settings row (U13)

- Settings UI is built as section controllers behind a **settings section host**: each section owns its views, painting, and persistence and talks to that contract; the activity implements the host and keeps lifecycle and wiring. A section's own model or store stays on the section ([ADR 0024](adr/0024-settings-section-controllers.md)). Progress of the split is tracked in [#264](https://github.com/NotCherry/LFM3Pocket/issues/264). In this doc, **Settings host** means the on-screen settings panel, not that host contract.
- Group on `surface` with optional section `headline`.
- Row: `label` + control (stepper, switch, field) aligned; `mono` for numeric/endpoint values where appropriate.
- Keep IDs: `settings_context`, `settings_threads`, `settings_tokens`, `search_*`, `import_*`.
- **Speech (optional tiny-voca):** Section `SPEECH` / `MOWA` with English-only checkbox (`settings_tiny_voca_tts`), download/remove pack actions (`settings_tiny_voca_download`, `settings_tiny_voca_remove`), and status line (`settings_tiny_voca_status`). Help text must state that the option is English-only, that a missing pack uses system TTS for English, and that non-English replies keep the Omni voice (ADR 0010).
- **Optional model downloads:** Cactus Whistle now, and the tiny-voca pack later, are user-initiated background transfers. They continue after the user leaves the app, run one at a time, and show progress in a low-importance notification with Cancel when notification permission is granted; denying `POST_NOTIFICATIONS` does not stop the transfer, only shade progress. The file is checksum-verified before an atomic install. User cancel or hard failure deletes `.partial` and leaves a previously verified model in place; a system interrupt keeps `.partial` and the next attempt downloads from byte zero (no Range resume). Before start, free space is checked. On a metered network (typically mobile data) the user confirms first; without that confirmation nothing is enqueued. A download started on an unmetered network without confirmation stays on unmetered networks and waits if the device switches to a metered one. Selecting or removing that model stays disabled while the download runs ([ADR 0021](adr/0021-optional-cactus-whistle-stt.md), [ADR 0022](adr/0022-background-model-downloads.md)).
- **Images:** Preparation is on by default (longest-side cap, optional square crop, JPEG quality). The user’s source file is never rewritten ([ADR 0005](adr/0005-prepare-vision-images.md)).
- **Calendar:** Writes require a calendar the user picked here. There is no silent default ([ADR 0014](adr/0014-calendar-tools-picked-calendar.md)).
- **Loopback:** Prompt-in from the same phone is off by default, token-gated, and cannot approve a tool ([ADR 0015](adr/0015-loopback-api-local-only.md)).

### 6.8 History list + reader (U12)

- List: charcoal rows, `ink` title, `inkMuted` preview/time — not Material outlined cards stack.
- **New chat** (`history_new_chat`) starts a new conversation (see 6.11).
- Reader: **same** turn / markdown / reasoning components as live chat for parity (UT-06).

### 6.9 Consent + camera chrome (U14)

- `ToolConfirmationActivity` and camera status surfaces use Atlas tokens (overlay charcoal, chalk text, steel accent primary).
- No CameraX rewrite; status strings remain clear and testable (`camera_status`, etc.).

### 6.10 Status pills

- Offline / search opt-in: compact label + optional accent outline; not a cluster of marketing chips.
- Live generation status (`chat_live_*`): readable chalk on charcoal; do not obscure the transcript.

### 6.11 Conversation lifecycle

- **Open chat** resumes the last live session.
- **New chat** (Home, History, and the chat top bar) starts a new conversation and a new history entry.
- **Clear** empties the transcript and keeps the same conversation.
- Starting a new chat while a reply is generating asks for confirmation, the same guard as switching conversations from History.
- **Compact** is a separate, user-visible step. It shortens the live prompt with a rolling summary stored on the conversation and restored when that conversation is opened again. The stored transcript stays intact ([ADR 0019](adr/0019-explicit-conversation-compact.md)).
- An attached document is untrusted context. That turn does not turn the reply into tool proposals ([ADR 0020](adr/0020-untrusted-local-documents.md)).
- The chat top-bar **New chat** action (`chat_new_chat`) uses an accent label and meets the Interface size minimum touch target (section 11). TalkBack announces its visible label.
- User-visible strings for these actions, including the default title of an untitled conversation, ship in English and Polish.

---

## 7. Motion

Ship a few intentional motions — presence and hierarchy, not noise.

| Motion | Behavior |
| --- | --- |
| Stream cursor | `▌` blink ~1Hz opacity pulse; stop blinking when generation ends. |
| Reasoning rail | Expand/collapse height + fade ~200–280ms; ease-in-out; default collapsed. |
| Send | Light scale or opacity flash on send affordance (~100–150ms); do not bounce the whole transcript. |
| Screen enter (Home) | Optional short fade/slide-up of CTA group (~250ms). Avoid perpetual background gradients animation. |

**Avoid:** parallax stacks, shimmer skeletons as brand, accent glow pulses, confetti.

---

## 8. Markdown subset (U07)

Render with in-house Compose `AnnotatedString` (no Markwon/WebView):

- Bold / italic
- Headings (map to type roles)
- Lists (ordered / unordered)
- Fenced code → `mono` on `codeBg`
- Links → `accent`, clickable

On parse failure: show plain text; never crash the turn list.

---

## 9. Content and sanitization (product copy rules)

- Answer body: **no** raw thinking XML/tag markers, Omni template junk, or special token spill.
- Strip/extract at session→UI boundary (U06); park reasoning in the rail field.
- Flat automator transcript on `chat_transcript` keeps `YOU` / `LFM3` / `▌` semantics for E2E (see [`UI_E2E_CONTRACT.md`](UI_E2E_CONTRACT.md)).
- Sources and tool results stay human-readable; tool JSON stays mono inside the panel.

---

## 10. Anti-patterns (explicit bans)

Do **not**:

1. Purple-on-white or purple-to-indigo “AI” gradients; Material default purple primary.
2. Warm cream (`~#F4F1EA`) + terracotta + high-contrast serif broadsheet look.
3. Neon glow, multi-layer shadows, glassmorphism stacks.
4. Rounded-full pill clusters and marketing chip rows in the hero.
5. Large inset hero image cards or tiled collages on Home.
6. Message bubbles that dominate with colored fills (open text blocks instead).
7. Showing raw thinking / special tokens in the answer body or as the only transcript.
8. Removing or renaming existing Stage 1 test IDs to “look cleaner.”
9. Claiming AT-01..AT-09 **VERIFIED** from Atlas screenshots alone.
10. Dashboard-of-cards Home or chat-first-viewport config strip reclaiming ~⅓ height (U10 removes that).

---

## 11. Accessibility, font scale, and i18n

Stillwater chrome must stay readable when the user enlarges text or display size, and when copy is longer (Polish). Prefer wrapping and flexible heights over shrinking text.

| Rule | Detail |
| --- | --- |
| Text size unit | Use **sp** for readable copy (View `setTextSize` / Compose `.sp`). Never size body or labels in **dp**. |
| Control height | Prefer `WRAP_CONTENT` with a **`minHeight`** floor. Do not hard-code fixed `dp` heights on buttons that can wrap. |
| Side-by-side rows | Keep import / open / idle / media rows **horizontal** on typical phone widths (~360–411dp) at default font. Stack to a column when `fontScale ≥ 1.3` or width &lt; **320dp** (`StillwaterReadable`). |
| Truncation | Do not ellipsize chrome labels (buttons, section titles). Dynamic previews (history snippets, long paths) may use bounded `maxLines` + ellipsis. |
| Locales | Ship **English** (`values/`) and **Polish** (`values-pl/`) for user-visible chrome. Polish strings are often longer — layouts must wrap, not clip. Brand `app_name` stays `translatable="false"`. |
| Interface size | Compact / Default / Large (or 80–130% slider) must not reintroduce clipping. Keep wrapping + `minHeight` (`StillwaterReadable`). |
| New Activity | Every Activity **must** call `InterfaceSize.wrap` from `attachBaseContext` and `InterfaceSize.recreateIfChanged` from `onResume` with the scale captured at attach (see [`docs/adr/0002-interface-size-density.md`](adr/0002-interface-size-density.md)). |
| Touch targets | Visual chrome may be denser; interactive hit areas stay ≥ **48dp** (Compose `minimumInteractiveComponentSize`, View `InterfaceSize.minTouchPx` using system density). |
| Tests | Emulator coverage: fontScale `0.85`–`2.0`, EN/PL, density variants, **and each interface-size preset** (`TextReadableAtScaleE2eTest`, `ComposeTextReadableTest`). Assert text is fully visible after layout, not only `assertIsDisplayed`. |

---

## 12. Implementation checklist (for PR authors)

- [ ] Colors come from Atlas tokens / `PocketTheme`, not one-off hex in composables (except this doc’s source of truth). Stage 1 CI partially enforces this ([ADR 0013](adr/0013-atlas-color-tokens-ci-hex-guard.md): 8-digit literals, soft chalk, `StillwaterUi` JVM pin — not the full spec ↔ palette matrix).
- [ ] New interactive chrome either reuses a recipe above or extends DESIGN.md in the same PR.
- [ ] E2E IDs preserved; flat transcript contract intact.
- [ ] UT row(s) noted in PR; matrix updated when evidence lands.
- [ ] Screenshots (if any) filed under device evidence for U16 — they do not flip AT rows.
- [ ] Font-scale / narrow-width / PL chrome still readable (section 11).

---

## 13. Inference performance (concept)

Atlas UI does not surface decode algorithms; inference lives on the Settings host. Besides DOTPROD
kernels and KV reuse (ADR 0001), Hanami may enable **llama.cpp ngram-mod prompt lookup** on text turns
(ADR 0011): a Settings checkbox, default off until arm64 measurement, no extra model download. If hybrid
memory cannot trim the prompt tail that lookup needs, that turn decodes sequentially. tok/s A/B is reported by the
existing benchmark controls (`settings_bench_run` / `settings_bench_result`).

## 14. References

| Doc / issue | Role |
| --- | --- |
| Tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) | U00 master checklist |
| [#118](https://github.com/NotCherry/LFM3Pocket/issues/118) | U01 this style guide |
| [#120](https://github.com/NotCherry/LFM3Pocket/issues/120) | U03 `PocketTheme` |
| [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md) | UT-01..UT-08 |
| [`UI_E2E_CONTRACT.md`](UI_E2E_CONTRACT.md) | Flat transcript + ID contract |
| [`ACCEPTANCE_MATRIX.md`](ACCEPTANCE_MATRIX.md) | Stage 1 AT-01..AT-09 (unchanged authority for product capability) |
| [`adr/0012-two-tier-emulator-e2e-gating.md`](adr/0012-two-tier-emulator-e2e-gating.md) | PR smoke vs deep/nightly emulator E2E gates |
| [`adr/0013-atlas-color-tokens-ci-hex-guard.md`](adr/0013-atlas-color-tokens-ci-hex-guard.md) | Normative Atlas color tokens and the CI hex guard |
| [`adr/README.md`](adr/README.md) | ADR index, including unused numbers 0008 and 0009 |
| [`adr/0003-turn-write-ahead.md`](adr/0003-turn-write-ahead.md) | Write the turn before inference; durable photo copy |
| [`adr/0005-prepare-vision-images.md`](adr/0005-prepare-vision-images.md) | Prepare vision images before the projector |
| [`adr/0022-background-model-downloads.md`](adr/0022-background-model-downloads.md) | Background downloads for optional pinned models |
| [`adr/0024-settings-section-controllers.md`](adr/0024-settings-section-controllers.md) | Settings sections behind a settings section host |
