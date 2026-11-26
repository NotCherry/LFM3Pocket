# ADR 0028 — Separate diagnostics export from lightweight multi-purpose GitHub issue flows

- Status: Accepted
- Date: 2026-11-26
- Related: [ADR 0004](0004-local-crash-log.md), [ADR 0024](0024-settings-section-controllers.md), [ADR 0027](0027-kotlin-first-adoption-and-incremental-migration.md), [`DIAGNOSTICS.md`](../DIAGNOSTICS.md), [`diagnostics-and-github-issues-plan.md`](../plans/diagnostics-and-github-issues-plan.md)

## Context

`CrashLogSettingsSection` and `DiagnosticsReportBuilder` currently expose four actions that heavily overlap in both payload and purpose:

1. **View crash log** (`settings_crash_log_view`): renders the full sanitized Markdown diagnostics report (`DiagnosticsReportBuilder.buildMarkdown(...)` — environment, processing trace, NDJSON events, crash/exit log).
2. **Export crash log** (`settings_crash_log_export`): sends that same full Markdown report to Android's `ACTION_SEND` share sheet.
3. **Report GitHub Issue** (`settings_diag_github_issue` → `openGitHubIssueDirect()`):
   - Copies the full Markdown diagnostics report to the clipboard, **and**
   - Opens `https://github.com/NotCherry/LFM3Pocket/issues/new` with a hardcoded bug title (`"Bug report: on-device processing / performance issue"`) and up to 4,800 characters of the **entire diagnostics/crash/NDJSON report** URL-encoded into `?body=...`.
4. **Upload Paste & Issue** (`settings_diag_paste_issue` → `confirmAndUploadPaste()`):
   - Uploads the full Markdown diagnostics report to `dpaste.org` / `paste.rs` after confirmation, **and**
   - Calls the exact same `buildGitHubIssueUrl(null, report, uploadedUrl)` — which **still** uses the hardcoded bug title and **still** stuffs the full 4,800-character diagnostics report into `?body=...` right underneath the paste link!

This creates three user-facing pain points:

- **Redundant controls doing the same thing:** "Export crash log", "Report GitHub Issue", and "Upload Paste & Issue" all emit essentially the same giant log payload; copying the log is hidden as a side effect of opening a GitHub bug report rather than being an explicit standalone action.
- **Bloated, fragile GitHub issue URLs:** Dumping up to 4,800 characters of raw trace/NDJSON/crash logs directly into a browser query string produces unwieldy URLs, truncates mid-block, and duplicates data already uploaded when using Paste.
- **Bug-only framing blocks ideas and general feedback:** Users who want to propose a feature, share a UX idea, ask a question, or report an install/device compatibility problem are forced through a "Crash log" card that pre-fills `"Bug report: on-device processing / performance issue"` and dumps crash/NDJSON logs into their issue body. Meanwhile, the repository has no `.github/ISSUE_TEMPLATE/` YAML forms to guide structured submissions.

## Decision

Split **on-device diagnostics/log management** from **GitHub issue creation**, give every button one distinct job, and support multiple issue intents (ideas/features, lightweight bug reports, device compatibility reports, and paste-backed full diagnostic reports).

### 1. Distinct local diagnostics actions (Settings → Diagnostics & Logs)
Each local control performs one predictable, offline action (preserving [ADR 0004](0004-local-crash-log.md)'s local-only default):
- **View log** (`settings_crash_log_view`): display sanitized diagnostics preview in-app.
- **Copy log** (new explicit action, `settings_diag_copy_log`): copy the full sanitized Markdown diagnostics report to the system clipboard and confirm via status line—**does not open a browser**.
- **Share / Export log** (`settings_crash_log_export`): open Android's share sheet with the full sanitized Markdown report.
- **Wi-Fi Log Viewer (No ADB)** (`settings_diag_lan_toggle`): start/stop the token-protected read-only LAN viewer (gated by `ACCESS_LOCAL_NETWORK` on Android 17+ per [ADR 0026](0026-android-12-to-17-compatibility-and-permission-gates.md)).
- **Clear logs** (`settings_crash_log_clear`): wipe local crash log and session telemetry.

### 2. Multi-purpose, lightweight "Create GitHub Issue" flow
**Create GitHub Issue** (`settings_diag_github_issue`, also linked from the **About** screen in `ComposeHostActivity`) no longer dumps logs or assumes every issue is a crash/bug. Tapping it opens a compact issue-type selector with four distinct templates:

| User choice | GitHub template param | Pre-filled payload in URL | Clipboard side effect |
| --- | --- | --- | --- |
| **Feature / Idea** | `template=feature_request.yml` | **Empty log payload.** Clean prompt (or optional 1-line app version only). No traces, no NDJSON, no crash log, no forced bug title. | None |
| **Bug report (device info only)** | `template=bug_report.yml` | **Compact Environment block ONLY** (~8 lines: App version, Device model, Android version + SDK, ABIs, RAM/thermal summary, active model basename & projector flag). **No** processing trace, NDJSON, or crash dump in the URL. | None (user uses **Copy log** if they want to paste logs manually) |
| **Device / Install compatibility** | `template=device_compatibility.yml` | **Compact Device/OS block ONLY** (Manufacturer/Model, Android release/SDK, ABIs, page size, App version). | None |
| **Blank / Other** | *(none)* | Completely empty (`https://github.com/NotCherry/LFM3Pocket/issues/new`) — zero pre-filled title or body. | None |

### 3. De-duplicated "Upload Paste & Bug Report" flow
**Upload Log (Paste) & Open Bug** (`settings_diag_paste_issue`) remains an explicit, opt-in action behind the redaction preview confirmation dialog, with one critical payload fix:
- After uploading the full sanitized Markdown diagnostics report to `dpaste.org` (fallback `paste.rs`), the opened GitHub Issue URL contains **ONLY**:
  1. `template=bug_report.yml` (and clean editable title prefix or blank title),
  2. The **Paste URL** (`### Full Sanitized Diagnostics Log\n<pasteUrl>`), and
  3. The **compact 8-line Environment summary** (never duplicating the 4,800-character raw trace/NDJSON/crash dump inside the query string!).
- Copies the short Paste URL (not the giant raw report) to the clipboard so the user can also share it elsewhere.

### 4. Structured repository issue forms (`.github/ISSUE_TEMPLATE/`)
Add GitHub Issue Forms in `.github/ISSUE_TEMPLATE/` so deep links from Hanami and direct visits on GitHub share the same clean experience:
- `bug_report.yml` — Bug report (steps, expected/actual, environment block, optional paste link)
- `feature_request.yml` — Feature request / Idea (problem/use-case, proposed solution, offline/privacy considerations)
- `device_compatibility.yml` — Device / Install / Performance report (phone model, OS/HyperOS/OEM skin, Android version, install source/error, GGUF model)
- `config.yml` — `blank_issues_enabled: true` so the **Blank / Other** option always works cleanly.

### 5. Implementation & compatibility rules
- Implement the URL/template builder in Kotlin (`GitHubIssueUrlBuilder.kt`, per [ADR 0027](0027-kotlin-first-adoption-and-incremental-migration.md)) with full JVM unit tests verifying every issue kind, URL-length bounds, secret/path redaction, and absence of trace/crash payloads in lightweight and paste-backed URLs.
- Preserve all existing resource IDs (`settings_crash_log_view`, `settings_crash_log_export`, `settings_crash_log_clear`, `settings_diag_github_issue`, `settings_diag_paste_issue`, `settings_diag_lan_toggle`) so E2E tests and [ADR 0024](0024-settings-section-controllers.md) ID contracts remain unbroken.
- Update both English (`res/values/strings.xml`) and Polish (`res/values-pl/strings.xml`) labels so the distinction between **Copy log**, **Export / Share log**, **Create GitHub Issue**, and **Upload Log & Bug Report** is unambiguous in both locales.

## Consequences

- **Positive:**
  - Every button in Diagnostics & Feedback has a single, non-overlapping purpose.
  - Users can easily submit feature ideas, UX suggestions, and device reports directly from Hanami without deleting walls of auto-pasted crash logs.
  - GitHub Issue URLs stay short (< 1,000 characters), reliable across mobile browsers and GitHub mobile app intent handlers, and never duplicate paste contents.
  - Preserves Hanami's strict privacy contract (`LocalDiagnostics.uploadsTelemetry() == false`; no automatic uploads; explicit preview before any paste upload).
- **Negative / Risks:**
  - Users filing a complex bug via **Bug report (device info only)** must either tap **Copy log** or use **Upload Log (Paste) & Bug Report** to include full traces. Clear helper copy in the chooser dialog makes this obvious.
  - Existing unit test `DiagnosticsAndTelemetryTest.diagnosticsReportSanitizesSecretsAndPaths` asserts on `DiagnosticsReportBuilder.buildGitHubIssueUrl`; we must update or keep a clean adapter for the new typed API while preserving full redaction coverage.

## Alternatives considered

- **Make "Create GitHub Issue" always open a completely blank `issues/new` URL with no device info at all.** Partially adopted as the **Blank / Other** option, but rejected as the *only* option: for bug and compatibility reports, having app version, device model, Android SDK, and ABI pre-filled saves huge triage back-and-forth without bloating the payload.
- **Use GitHub API with an OAuth flow inside Hanami to submit issues natively.** Rejected. Adds OAuth complexity, token storage risk, and network coupling to an offline-first app. Browser-launched GitHub Issue Forms are zero-token, zero-secret, and let the user review and edit everything before submitting.
- **Remove the Paste uploader completely.** Rejected. On Android devices without ADB, 7-day expiring HTTPS paste upload with an explicit redaction preview dialog remains the lowest-friction way to attach long native traces when a user *does* want to share full logs.
