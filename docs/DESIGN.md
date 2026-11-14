# LFM3 Pocket — Atlas design system

Authoritative visual and interaction guide for the **Atlas** UI epic (tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) / U00). Implementers of `PocketTheme` (#120 / U03) and screen issues (#126–#131) must follow this document.

Atlas is a **dark editorial** direction: charcoal field, chalk ink, steel-blue accent, open text blocks, bordered mono tool panels, and a left **Reasoning** rail (collapsed by default). It does **not** replace Stage 1 AT-01..AT-09 gates; UI acceptance is tracked as UT-01..UT-08 in [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md).

---

## 1. Brand and composition rules

| Rule | Requirement |
| --- | --- |
| Brand first | On Home and chat chrome, **LFM3** (or product wordmark) is a hero-level signal, not only nav text. |
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
| `surface` | `#0E1116` | App background (charcoal). Full-bleed. |
| `surfaceRaised` | `#151A21` | Slightly lifted panels (composer bar, tool panel fill, settings groups). Still charcoal family — not grey Material cards. |
| `surfaceOverlay` | `#1C232D` | Modals, consent sheet, overflow menus. |
| `ink` | `#E8EAED` | Primary chalk text (body, headlines). |
| `inkMuted` | `#9AA3AD` | Secondary text, timestamps, hints, collapsed rail label. |
| `inkDisabled` | `#5C6670` | Disabled controls and placeholder. |
| `accent` | `#5B8FA8` | Steel-blue: links, focus ring, primary CTA fill/outline, source chips, active pill. |
| `accentMuted` | `#3D6478` | Pressed/hover accent, subtle selection. |
| `border` | `#2A3340` | Hairline borders for tool panels, composer, rail, inputs. |
| `borderStrong` | `#3E4A5A` | Emphasized borders (focused input, selected tool). |
| `danger` | `#C45B5B` | Destructive / Reject — desaturated red, not neon. |
| `ok` | `#6B9B7A` | Success / approved tool result — muted green. |
| `warn` | `#C4A35A` | Caution / elevated tool risk — muted amber. |
| `codeBg` | `#0A0D11` | Fenced code / mono blocks (slightly deeper than surface). |
| `cursor` | `#E8EAED` | Streaming block cursor `▌` (chalk). |

**Contrast:** Body text (`ink` on `surface`) must remain clearly readable outdoors-ish; do not drop body below chalk on charcoal. Accent is for affordances, not large filled backgrounds behind paragraphs.

**Material3 mapping (U03):**

| M3 role | Atlas token |
| --- | --- |
| `background` / `surface` | `surface` |
| `surfaceVariant` | `surfaceRaised` |
| `onBackground` / `onSurface` | `ink` |
| `onSurfaceVariant` | `inkMuted` |
| `primary` | `accent` |
| `onPrimary` | `surface` (dark ink on steel fill) |
| `outline` | `border` |
| `error` | `danger` |

Atlas epic is **dark-only**; no light ColorScheme required.

---

## 3. Typography

Prefer a purposeful pairing already shippable on Android without new font APK bloat unless the project already vendors fonts:

| Role | Spec | Use |
| --- | --- | --- |
| `display` | Sans, semi-bold/bold, ~32–40sp, tight tracking | Home brand / hero product name |
| `headline` | Sans, semi-bold, ~22–28sp | Screen titles (Settings, History) |
| `title` | Sans, medium, ~18–20sp | Section titles, turn role labels if shown |
| `body` | Sans, regular, ~16sp, line height ~1.35–1.45 | Assistant and user message bodies |
| `bodySmall` | Sans, regular, ~13–14sp | Captions, timestamps, rail collapsed hint |
| `label` | Sans, medium, ~12–13sp, slight letter-spacing | Pills, buttons, settings row labels |
| `mono` | Monospace (e.g. `FontFamily.Monospace`), ~13–14sp | Tool names, JSON args, paths, fenced code, risk codes |

**Rules**
- User vs assistant: same `body` size; distinguish by subtle role label or left gutter — **not** by large colored bubbles.
- Markdown headings map into `title` / `headline` scale; do not introduce a third decorative display face in chat.
- Avoid default “AI purple” or Inter-as-brand; system sans is acceptable if roles above are consistent.

---

## 4. Spacing and layout scale

Base unit **4dp**. Prefer this scale:

| Token | dp |
| --- | --- |
| `space2` | 8 |
| `space3` | 12 |
| `space4` | 16 |
| `space5` | 20 |
| `space6` | 24 |
| `space8` | 32 |
| `space10` | 40 |
| `space12` | 48 |

**Layout**
- Screen horizontal padding: `space4` (16) phone; keep content readable, not edge-glued.
- Turn vertical gap: `space4`–`space5`.
- Composer inner padding: `space3`–`space4`.
- Reasoning rail width (expanded): ~36–42% of chat width on phone landscape / large; on narrow portrait, rail becomes a top-of-turn collapsible strip still **leading** (start side) aligned.
- Corner radius: **4–8dp** for inputs and tool panels. Avoid pill (`rounded-full`) chrome except tiny status dots.

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
- CTA group: primary **Open chat** (accent filled or strong outline), secondary text buttons **History**, **Settings**, **About**.
- No cards, no stat strip, no inset hero image collage.
- Preserve E2E: history open control (`history_open` / existing host tags).

### 6.2 Chat turn (`ChatTurnList` / U08)

- Open text block on `surface` — **not** a Material message bubble card.
- Optional quiet role marker (`YOU` / `LFM3`) in `label` + `inkMuted`.
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

- Bordered panel: `surfaceRaised` fill, `border` stroke, radius 6–8dp.
- Header: tool name in `mono` + optional risk tint (`warn` / `danger` / `ok`).
- Body: arguments / details in `mono`, selectable where useful.
- Actions: **Approve** / **Reject** as clear buttons; keep stable IDs `tool_approve`, `tool_reject`, `tool_details`, `tool_status` (add `tool_risk`, `tool_result` per U11).
- This is one of the few justified “card-like” containers because it wraps a consent interaction.

### 6.5 Composer (U11)

- Anchored bottom bar on `surfaceRaised` with top `border`.
- Multiline field: chalk caret, `ink` text, placeholder `inkDisabled`.
- Send uses accent; Stop remains obvious during generation (`chat_send` / `chat_stop`).
- Voice / camera / photo affordances stay reachable without reclaiming ⅓ of the viewport for config.

### 6.6 Media chip

- Small preview thumbnail + remove control (`image_preview`, `image_remove`, etc.).
- Border `border`; no floating sticker overlay on the transcript hero.

### 6.7 Settings row (U13)

- Group on `surface` with optional section `headline`.
- Row: `label` + control (stepper, switch, field) aligned; `mono` for numeric/endpoint values where appropriate.
- Keep IDs: `settings_context`, `settings_threads`, `settings_tokens`, `search_*`, `import_*`.

### 6.8 History list + reader (U12)

- List: charcoal rows, `ink` title, `inkMuted` preview/time — not Material outlined cards stack.
- Reader: **same** turn / markdown / reasoning components as live chat for parity (UT-06).

### 6.9 Consent + camera chrome (U14)

- `ToolConfirmationActivity` and camera status surfaces use Atlas tokens (overlay charcoal, chalk text, steel accent primary).
- No CameraX rewrite; status strings remain clear and testable (`camera_status`, etc.).

### 6.10 Status pills

- Offline / search opt-in: compact label + optional accent outline; not a cluster of marketing chips.
- Live generation status (`chat_live_*`): readable chalk on charcoal; do not obscure the transcript.

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

## 11. Implementation checklist (for PR authors)

- [ ] Colors come from Atlas tokens / `PocketTheme`, not one-off hex in composables (except this doc’s source of truth).
- [ ] New interactive chrome either reuses a recipe above or extends DESIGN.md in the same PR.
- [ ] E2E IDs preserved; flat transcript contract intact.
- [ ] UT row(s) noted in PR; matrix updated when evidence lands.
- [ ] Screenshots (if any) filed under device evidence for U16 — they do not flip AT rows.

---

## 12. References

| Doc / issue | Role |
| --- | --- |
| Tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135) | U00 master checklist |
| [#118](https://github.com/NotCherry/LFM3Pocket/issues/118) | U01 this style guide |
| [#120](https://github.com/NotCherry/LFM3Pocket/issues/120) | U03 `PocketTheme` |
| [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md) | UT-01..UT-08 |
| [`UI_E2E_CONTRACT.md`](UI_E2E_CONTRACT.md) | Flat transcript + ID contract |
| [`ACCEPTANCE_MATRIX.md`](ACCEPTANCE_MATRIX.md) | Stage 1 AT-01..AT-09 (unchanged authority for product capability) |
