# Validated Android tool protocol

A model proposes one JSON object, for example:

```json
{"tool":"set_timer","arguments":{"seconds":60,"label":"Tea"}}
```

`ToolRegistry.parse` returns either `VALIDATED` with an immutable typed request or a structured `INVALID_JSON`, `INVALID_REQUEST`, `UNKNOWN_TOOL` or `INVALID_ARGUMENTS` result with no request. Parsing does not authorize or execute an action.

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `toggle_flashlight` | `on`: boolean | — |
| `set_volume` | `stream`: music/alarm/ring/notification; `percent`: integer 0–100 | — |
| `set_timer` | `seconds`: integer 1–86400 | `label`: single-line text, at most 100 UTF-16 units; default empty |
| `set_alarm` | `hour`: integer 0–23; `minute`: integer 0–59 | `label`: same as timer; `days`: unique Calendar weekdays 1–7 |
| `show_alarms` | — | — |
| `open_application` | `package`: package identifier, at most 255 characters | — |
| `open_settings` | `page`: wifi/bluetooth/sound/display/application | — |
| `copy_text` | `text`: 0–4000 UTF-16 units | — |
| `share_text` | `text`: 1–4000 UTF-16 units | — |
| `current_time` | — | `timezone`: IANA id |
| `calculator` | `expression`: safe arithmetic, at most 200 characters | — |
| `unit_convert` | `value`: number; `from` / `to`: common length/mass/temperature/volume/speed units | — |
| `weather` | `location` **or** `latitude`+`longitude` | — |

Timers, alarms, and show-alarms use `android.provider.AlarmClock` intents (`ACTION_SET_TIMER` / `ACTION_SET_ALARM` / `ACTION_SHOW_ALARMS`) with the normal `com.android.alarm.permission.SET_ALARM` permission. Consent UI reads the exact action back before firing. Missing clock apps return `CLOCK_APP_UNAVAILABLE`.

`weather` is gated by the Settings **network tools** toggle (default off) and calls Open-Meteo over HTTPS with no API key. Offline or toggle-off failures stay non-success. Built-in tool messages are prefixed `UNTRUSTED_*` so callers treat them as untrusted data.

Unknown keys, type coercions, non-integer numeric literals for integer fields, missing arguments and out-of-range values are rejected. Text cannot contain NUL; labels cannot contain control characters.

The JSON reader accepts standard JSON only: no comments, single-quoted/unquoted keys, duplicate keys, trailing commas, multiple objects, Markdown fences, invalid escapes or unpaired surrogates. It limits input to 8192 UTF-16 units, nesting to four levels, and object/array entries to 64.

## Origin, risk and one-shot consent

`ToolCoordinator` accepts only immutable validated requests. App code supplies origin separately; JSON cannot claim user authority. An explicit app control with the exact arguments is `USER_ACTION`; ordinary model/chat output is `MODEL_PROPOSAL` and always needs approval. Image, web-search, document, background and unknown sources cannot authorize actions.

Flashlight, timer creation **request**, show-alarms, installed-app launch, fixed settings pages, music volume, current time, calculator, unit conversion and weather are SAFE. Alarm, non-music volume, clipboard and sharing require confirmation; unknown risk defaults to confirmation. Individual handlers still enforce Android permissions, availability and policy. The coordinator checks permission/policy both before proposing and immediately before executing.

Pending proposals expire after 60 seconds using a monotonic clock and have random IDs; at most 32 may be pending. Internal, non-exported `ToolConfirmationActivity` receives only an ID and displays the stored action and exact arguments, escaping control/bidi text. Approve consumes the proposal before execution; rejection, timeout, permission revocation and replay produce no new side effect.
