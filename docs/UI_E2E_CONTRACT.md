# Atlas UI — E2E contract

Owner: [#121](https://github.com/NotCherry/LFM3Pocket/issues/121) (U04). Tracker: [#135](https://github.com/NotCherry/LFM3Pocket/issues/135).

Automator-facing chat contract for Atlas structured turns. Visual recipes live in [`DESIGN.md`](DESIGN.md); UT status in [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md).

## Goals

1. Keep every existing stable ID in `app/src/main/res/values/ids.xml` (and current `testTag`s) discoverable.
2. Expose a **flat transcript** string on `chat_transcript` semantics even after bubble/turn UI lands.
3. Document how structured turns map into that flat text.

## Flat transcript format

Production helper: `com.notcherry.lfm3pocket.FlatTranscriptFormatter` (JVM-tested).

Lines (or concatenated blocks) must remain greppable by existing E2E:

| Marker | Meaning |
| --- | --- |
| `YOU` | User turn text |
| `LFM3` | Assistant answer body (cleaned — no raw thinking tags). This greppable marker stays `LFM3` so existing automators keep working. The visible role label is **Hanami**. |
| `▌` | Streaming cursor while generating |

Empty conversation placeholder (historical):

```text
No conversation yet.
```

### Structured → flat mapping

| Structured input | Flat output |
| --- | --- |
| Empty turn list | `No conversation yet.\n` |
| User turn `text` | `YOU\n{text}\n\n` |
| Completed assistant `text` | `LFM3\n{text}\n\n` then optional `[n] {title}\n{url}\n` sources |
| Streaming assistant `text` | `LFM3\n{text}▌` (no trailing blank line; sources omitted while streaming) |

**Rules**

- Reasoning rail content is **not** included in the flat string for v1; if included later, it must not re-introduce raw tag markers.
- Tool proposals / results stay reachable via tool IDs; do not rely solely on flat text for Approve/Reject.
- Partial streams update the active `LFM3` segment and keep `▌` until finish/cancel.
- Cancelled turns finish as completed assistant text **without** `▌` (same as today's `ChatSession.finish`).
- `FlatTranscriptFormatter` does **not** sanitize thinking tags; U06 supplies cleaned `text` before format.
- Production (U08/U15): ChatPanel owns a real Android `R.id.chat_transcript` TextView (sibling above the Compose Atlas turns — VISIBLE, non-zero bounds, in the accessibility tree). Its text/`contentDescription` come from the helper. Do not bury that host inside an occluded/alpha-0 Compose `AndroidView`, mark it `IMPORTANT_FOR_ACCESSIBILITY_NO`, or shadow it with a Compose `testTag` of the same resource-id.

### Formatter API

```text
FlatTranscriptFormatter.format(List<Turn>) -> String
FlatTranscriptFormatter.format(Turn...) -> String

Turn(role: USER|ASSISTANT, text: String, streaming: Boolean = false, sources: List<Source> = [])
Source(title: String, url: String)
```

Constants: `EMPTY_TRANSCRIPT`, `USER_MARKER` (`YOU`), `ASSISTANT_MARKER` (`LFM3`), `STREAM_CURSOR` (`▌`).

JVM evidence: `FlatTranscriptFormatterTest` (multi-turn, streaming cursor, cancelled, sources, cleaned pass-through).

## Stable IDs (must preserve)

Complete checklist from `app/src/main/res/values/ids.xml`:

| ID | Role |
| --- | --- |
| `model_configuration_scroll` | Model/config strip scroll host |
| `chat_status` | Status line |
| `chat_live_status` | Live generation status text |
| `chat_live_mode` | Live mode label |
| `chat_live_progress` | Live progress indicator |
| `chat_live_announcer` | Live region announcer |
| `chat_transcript` | Flat automator transcript host |
| `chat_input` | Composer field |
| `chat_send` | Send |
| `chat_stop` | Stop generation |
| `voice_record` | Voice input |
| `voice_speak` | Voice output |
| `import_model` | Model GGUF import |
| `import_projector` | Projector import |
| `settings_open` | Chat top-bar entry into Settings host |
| `settings_back` | Leave Settings back to chat |
| `settings_context` | Context-length setting |
| `settings_threads` | Threads setting |
| `settings_tokens` | Max-tokens setting |
| `settings_bench_run` | Run llama-bench style pp/tg speed test |
| `settings_bench_result` | Wrapping benchmark report text |
| `settings_bench_copy` | Copy benchmark results |
| `settings_build_info` | Native build / ARM feature summary |
| `settings_save` | Save inference limits (not AlertDialog `android:button1`) |
| `search_save` | Save SearXNG endpoint / opt-in |
| `tool_details` | Tool card details |
| `tool_status` | Tool card status |
| `tool_approve` | Tool Approve |
| `tool_reject` | Tool Reject |
| `chat_camera` | Open camera |
| `camera_status` | Camera status |
| `camera_preview` | Camera preview |
| `camera_cancel` | Cancel capture |
| `camera_capture` | Capture |
| `chat_photo` | Photo picker |
| `image_attachment` | Attachment row |
| `image_preview` | Attachment preview |
| `image_remove` | Remove attachment |
| `web_search` | Web search control |
| `search_endpoint` | SearXNG endpoint field |
| `search_enabled` | Search opt-in |

**Compose `testTag` resource-id style** (package-qualified): same names as above via `"{package}:id/{name}"`. Host/history: `history_open` (and other host tags used by E2E) remain stable under U09/U12.

**Additive IDs (U11/U13):** `tool_risk`, `tool_result`, `settings_open`, `settings_back`, `chat_overflow`, `settings_save`, `search_save` — keep discovering the Stage 1 chat IDs above; do not replace them.

**Do not** rename or remove IDs to “look cleaner.”

## Settings navigation (U10 / U13 / U15)

Model import, inference steppers, and SearXNG controls live on the **Settings host** (hidden until opened). EMU-FAKE tests that touch `model_configuration_scroll`, `import_*`, `web_search`, `settings_*`, or `search_*` must:

1. Wait for chat ready (`chat_send`).
2. Open Settings via `settings_open` (or overflow → Settings).
3. Interact inside `model_configuration_scroll`.
4. Save inference with `settings_save`; save search with `search_save` (never `android:button1`).
5. Return to chat via `settings_back` before asserting `chat_send` / composer IDs (those hosts are `GONE` while Settings is open).

Scripted fixtures that emit raw think tags must leave **no** `<think>` / `</think>` markers in `chat_transcript` (see `ChatUiE2eTest.scriptedThinkTagsDoNotAppearInFlatTranscript`).

## Dependency note

U15 adapts Stage 1 E2E selectors/navigation after Atlas Settings host + flat transcript wiring. Do not delete assertions; fix navigation and Save IDs here.

## Out of scope here

- Visual Atlas recipes → [`DESIGN.md`](DESIGN.md)
- UT status tracking → [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md)
- Changing AT-01..AT-09 VERIFIED rules
- ChatTurnList / Reasoning rail UI (U08)
