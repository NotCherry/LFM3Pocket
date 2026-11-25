# Validated Android tool protocol

Hanami supports **Liquid AI LFM3 native XML tool calls** (primary), **LFM2/LFM2.5 Pythonic `<|tool_call_start|>` calls**, and legacy single-object JSON:

```xml
<tool_call>
<function=set_timer>
<parameter=seconds>60</parameter>
<parameter=label>Tea</parameter>
</function>
</tool_call>
```

```json
{"tool":"set_timer","arguments":{"seconds":60,"label":"Tea"}}
```

`ToolRegistry.parse` / `ToolRegistry.parseCall` returns either `VALIDATED` with an immutable typed request or a structured `INVALID_JSON`, `INVALID_REQUEST`, `UNKNOWN_TOOL` or `INVALID_ARGUMENTS` result with no request. Parsing does not authorize or execute an action.

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `toggle_flashlight` | `on`: boolean | — |
| `set_volume` | `stream`: music/alarm/ring/notification; `percent`: integer 0–100 | — |
| `set_timer` | `seconds`: integer 1–86400 | `label`: single-line text, at most 100 UTF-16 units; default empty |
| `set_alarm` | `hour`: integer 0–23; `minute`: integer 0–59 | `label`: same as timer; `days`: unique Calendar weekdays 1–7 |
| `set_reminder` | `when` (local ISO-8601 `YYYY-MM-DDTHH:MM`) **or** `when_epoch_sec` (`now+60s..now+366 days`); `title`: 0–100 UTF-16 units, one line, no HTML | `body`: 0–500 UTF-16 units, no HTML (default empty when using `when`) |
| `show_alarms` | — | — |
| `open_application` | `package`: package identifier, at most 255 characters | — |
| `open_settings` | `page`: wifi/bluetooth/sound/display/application | — |
| `copy_text` | `text`: 0–4000 UTF-16 units | — |
| `share_text` | `text`: 1–4000 UTF-16 units | — |
| `current_time` | — | `timezone`: IANA id |
| `calculator` | `expression`: safe arithmetic, at most 200 characters | — |
| `unit_convert` | `value`: number; `from` / `to`: common length/mass/temperature/volume/speed units | — |
| `weather` | `location` **or** `latitude`+`longitude` | — |
| `web_search` | `query`: 1–300 UTF-16 units, single line | `time_range`: `day` / `week` / `month` / `year` |
| `list_notifications` | `limit`: integer 1–50; `package`: package identifier or empty string | — |
| `list_events` | `window_start` / `window_end` (ISO-8601) **or** `window_start_epoch_sec` / `window_end_epoch_sec` (bounded window) | `limit`: integer 1–50 (default 20) |
| `create_event` | `title`: 1–200 UTF-16 units; `start` (ISO-8601) **or** `start_epoch_sec`+`end_epoch_sec` | `end` / `duration_minutes` (default +1h, or +1d for date-only); `calendar_id` (defaults to picked Settings calendar); `all_day`: boolean; `location`: 0–2000; `notes`: 0–4000 |
| `update_event` | `event_id`: opaque id; `title`: 1–200 UTF-16 units; `start`+`end` (ISO-8601) **or** `start_epoch_sec`+`end_epoch_sec` | — |
| `delete_event` | `event_id`: opaque id | — |
| `draft_message` | `channel`: `sms` or `share`; for `sms`: `to` (1–64 UTF-16 units), `body` (1–2000); for `share`: `package` (allowlisted), `body` (1–4000) | `to`: optional for `share` (0–64 UTF-16 units) |
| `send_message` | `draft_id`: opaque id from a prior `draft_message` | — |

Timers, alarms, and show-alarms use `android.provider.AlarmClock` intents (`ACTION_SET_TIMER` / `ACTION_SET_ALARM` / `ACTION_SHOW_ALARMS`) with the normal `com.android.alarm.permission.SET_ALARM` permission. Consent UI reads the exact action back before firing. Missing clock apps return `CLOCK_APP_UNAVAILABLE`.

`set_reminder` schedules a local notification reminder via inexact `WorkManager` (persisted across process death/restart). Posting requires `POST_NOTIFICATIONS` on Android 13+. No exact alarm permission is requested ([ADR 0007](adr/0007-voice-reminders-workmanager.md)).

`weather` is gated by the Settings **network tools** toggle (default off) and calls Open-Meteo over HTTPS with no API key. `web_search` is gated by the Settings **Web search** toggle and configured HTTPS SearXNG endpoint (default off). Offline or toggle-off failures stay non-success. Built-in tool messages are prefixed `UNTRUSTED_*` internally so prompts treat them as untrusted data, while UI cards strip internal markers for readability.

`list_notifications` is opt-in and default off ([ADR 0018](adr/0018-notification-list-opt-in.md)). It requires the in-app notification access master toggle, a non-empty per-app allowlist (empty deny-all), and system notification listener access for Hanami. Results include only allowed packages, are prefixed `UNTRUSTED_NOTIFICATION_DATA`, and must be treated as untrusted: notification bodies/titles may contain prompt-injection text and cannot authorize tool proposals or actions. No tap/dismiss actions are exposed by this tool.

`draft_message` stores an SMS or allowlisted share payload locally and returns a draft id; it does not open another app ([ADR 0017](adr/0017-message-tools-open-composer.md)). `send_message` takes that draft id and opens the target app’s composer so the user can complete or abandon the message there. It does not carry a new body, and Hanami does not send the message.

Calendar tools use `CalendarContract` ([ADR 0014](adr/0014-calendar-tools-picked-calendar.md)). `list_events` requires runtime `READ_CALENDAR` permission. `create_event`, `update_event`, and `delete_event` require runtime `WRITE_CALENDAR` permission (requested together with `READ_CALENDAR` in the Settings calendar picker) and a user-picked writable target calendar in Settings; writes are blocked without that picked calendar. When `create_event` omits `calendar_id`, Hanami targets the picked Settings calendar automatically; if `calendar_id` is explicitly provided, it must match the picked calendar. `update_event` and `delete_event` verify that the target event belongs to the picked calendar, and all-day events snap to UTC midnight boundaries per `CalendarContract`. Event and calendar IDs are opaque. Tool results and listed event data are untrusted (`UNTRUSTED_CALENDAR`) and cannot authorize actions. Stale event IDs fail closed on update/delete.

Unknown keys, type coercions, non-integer numeric literals for integer fields, missing arguments and out-of-range values are rejected. Text cannot contain NUL; labels cannot contain control characters.

The JSON reader accepts standard JSON only: no comments, single-quoted/unquoted keys, duplicate keys, trailing commas, multiple objects, Markdown fences, invalid escapes or unpaired surrogates. It limits input to 8192 UTF-16 units, nesting to four levels, and object/array entries to 64.

## Origin, risk, multi-turn loop and one-shot consent

`ChatSession` embeds `LfmToolCatalog` (`<tools>...</tools>`) in the static system prompt (preserving KV prefix reuse) and injects local clock/capability grounding on the active user turn. Inside a bounded multi-turn loop (max 4 steps), permitted read-only helpers (`current_time`, `calculator`, `unit_convert`, opt-in `weather`, opt-in `web_search`, and `list_events` when `READ_CALENDAR` is granted) execute and return compact `<tool_response>` messages so the model can synthesize a final answer. Side-effect and write tools always stop the loop and require explicit confirmation via `ToolCoordinator`.

`ToolCoordinator` accepts only immutable validated requests. App code supplies origin separately; JSON/XML cannot claim user authority. An explicit app control with the exact arguments is `USER_ACTION`; ordinary model/chat output is `MODEL_PROPOSAL` and always needs approval. Image, web-search, document, background and unknown sources cannot authorize actions.

Flashlight, timer creation **request**, show-alarms, installed-app launch, fixed settings pages, music volume, current time, calculator, unit conversion, weather and web search are SAFE. Alarm, reminder, non-music volume, clipboard and sharing, and all calendar tools require confirmation; unknown risk defaults to confirmation. Individual handlers still enforce Android permissions, availability and policy. The coordinator checks permission/policy both before proposing and immediately before executing.

Pending proposals expire after 60 seconds using a monotonic clock and have random IDs; at most 32 may be pending. Internal, non-exported `ToolConfirmationActivity` receives only an ID and displays the stored action and human-readable arguments, escaping control/bidi text. Approve consumes the proposal before execution; rejection, timeout, permission revocation and replay produce no new side effect.
