# Validated Android tool protocol

A model proposes one JSON object, for example:

```json
{"tool":"set_timer","arguments":{"seconds":60,"label":"Tea"}}
```

`ToolRegistry.parse` returns either `VALIDATED` with an immutable typed request or a structured `INVALID_JSON`, `INVALID_REQUEST`, `UNKNOWN_TOOL` or `INVALID_ARGUMENTS` result with no request. Parsing does not authorize or execute an action. The production Android executor remains default-deny until individual handlers are installed (#35–#43).

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `toggle_flashlight` | `on`: boolean | — |
| `set_volume` | `stream`: music/alarm/ring/notification; `percent`: integer 0–100 | — |
| `set_timer` | `seconds`: integer 1–86400 | `label`: single-line text, at most 100 UTF-16 units; default empty |
| `set_alarm` | `hour`: integer 0–23; `minute`: integer 0–59 | `label`: same as timer |
| `open_application` | `package`: package identifier, at most 255 characters | — |
| `open_settings` | `page`: wifi/bluetooth/sound/display/application | — |
| `copy_text` | `text`: 0–4000 UTF-16 units | — |
| `share_text` | `text`: 1–4000 UTF-16 units | — |

Unknown keys, type coercions, non-integer numeric literals for integer fields, missing arguments and out-of-range values are rejected. Text cannot contain NUL; labels cannot contain control characters. The application handler must still resolve the explicitly identified installed package and the settings handler must map page names to fixed intents; the model supplies neither executable commands nor arbitrary intents.

The JSON reader accepts standard JSON only: no comments, single-quoted/unquoted keys, duplicate keys, trailing commas, multiple objects, Markdown fences, invalid escapes or unpaired surrogates. It limits input to 8192 UTF-16 units, nesting to four levels, and object/array entries to 64. It does not scrape an actionable substring from ordinary model prose or untrusted image/search text. Exact user-origin and consent checks belong to #34, before any handler can execute a validated request.

`ToolRegistryTest` covers all eight schemas, malformed/malicious inputs, unknown tools, immutability, Unicode, range/type errors and 1000 seeded hostile strings. This is JVM validation evidence, not Android side-effect or model tool-calling acceptance.

## Origin, risk and one-shot consent

`ToolCoordinator` accepts only immutable validated requests. App code supplies origin separately; JSON cannot claim user authority. An explicit app control with the exact arguments is `USER_ACTION`; ordinary model/chat output is `MODEL_PROPOSAL` and always needs approval. Image, web-search, document, background and unknown sources cannot authorize actions.

Flashlight, timer creation **request**, installed-app launch, fixed settings pages and music volume are SAFE. Alarm, non-music volume, clipboard and sharing require confirmation; unknown risk defaults to confirmation. Individual handlers must still enforce Android permissions, availability and policy. The coordinator checks permission/policy both before proposing and immediately before executing.

Pending proposals expire after 60 seconds using a monotonic clock and have random IDs; at most 32 may be pending. Internal, non-exported `ToolConfirmationActivity` receives only an ID and displays the stored action and exact arguments, escaping control/bidi text. Approve consumes the proposal before execution; rejection, timeout, permission revocation and replay produce no new side effect. Back/finish rejects unresolved consent. Configuration recreation preserves in-process proposals; process death loses them and fails closed. Outcomes report the handler's actual status, including failure; approval does not imply successful execution. Completed in-process outcomes are bounded to 64 records.

`ToolCoordinatorTest` covers origin/risk, permission revocation, timeout, denial, replay and exceptions. `ToolConsentE2eTest` drives the real consent Activity with an injected counting handler: approval once, rejection, timeout, failure and recreation. These prove authorization mechanics, not device tool execution or real model tool-calling quality. Chat proposal cards remain #42.
