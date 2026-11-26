# Plan & Requirements: Separate Diagnostics Export from Multi-Purpose GitHub Issue Flows

- **Status:** Implemented
- **Date:** 2026-11-26
- **Governing ADR:** [ADR 0028 — Separate diagnostics export from lightweight multi-purpose GitHub issue flows](../adr/0028-separate-diagnostics-export-from-github-issues.md)
- **Related ADRs:** [ADR 0004](../adr/0004-local-crash-log.md), [ADR 0024](../adr/0024-settings-section-controllers.md), [ADR 0027](../adr/0027-kotlin-first-adoption-and-incremental-migration.md)
- **Related Docs:** [`DIAGNOSTICS.md`](../DIAGNOSTICS.md)

---

## 1. Current State vs. Target UX Matrix

### 1.1 Current Pain Points (`CrashLogSettingsSection.java` + `DiagnosticsReportBuilder.java`)

| Current Control | Current Behavior | Problem |
| --- | --- | --- |
| **View crash log** (`settings_crash_log_view`) | Displays full Markdown report (Environment + Trace + NDJSON + Crash). | Fine, stays local. |
| **Export crash log** (`settings_crash_log_export`) | Shares full Markdown report via Android share sheet (`ACTION_SEND`). | Fine, stays local. |
| **Report GitHub Issue** (`settings_diag_github_issue`) | Copies full Markdown report to clipboard **AND** opens `issues/new` with hardcoded title `"Bug report: on-device processing / performance issue"` **AND** dumps up to 4,800 chars of full traces/NDJSON/crash log into `?body=...`. | Duplicates Export/Copy; assumes every issue is a bug; bloats browser URL; hides "Copy log" as a side effect. |
| **Upload Paste & Issue** (`settings_diag_paste_issue`) | Uploads full Markdown report to `dpaste.org`/`paste.rs` **AND** calls `buildGitHubIssueUrl(null, report, uploadedUrl)` which **still** uses the hardcoded bug title and **still** dumps up to 4,800 chars of the same raw report into `?body=...`. | Duplicates the uploaded paste log inside the GitHub URL! |
| *(Missing)* | No standalone **Copy log** button. No way to create a **Feature / Idea**, **Device compatibility**, or **clean/blank** GitHub issue. No `.github/ISSUE_TEMPLATE/` forms. | Users cannot suggest ideas or open lightweight issues without deleting pages of logs. |

### 1.2 Target Separation of Concerns

| Control | Resource ID (Preserved / New) | Exact Behavior & Payload | Network? |
| --- | --- | --- | --- |
| **View log** | `settings_crash_log_view` *(existing)* | Renders full sanitized Markdown diagnostics report in-app. | Offline |
| **Copy log** | `settings_diag_copy_log` *(new)* | Copies full sanitized Markdown diagnostics report to clipboard. Status: `"Copied sanitized diagnostics log."` Does **not** open a browser. | Offline |
| **Share / Export** | `settings_crash_log_export` *(existing)* | Opens Android share sheet (`ACTION_SEND`, `text/plain`) with full sanitized Markdown report. | Offline (OS share) |
| **Wi-Fi Log Viewer** | `settings_diag_lan_toggle` *(existing)* | Starts/stops local token-gated read-only HTTP viewer (`LanDiagnosticsServer`; gated by `ACCESS_LOCAL_NETWORK` on API 37+). | LAN only |
| **Clear log** | `settings_crash_log_clear` *(existing)* | Clears local crash log and session telemetry. | Offline |
| **Create GitHub Issue** | `settings_diag_github_issue` *(existing ID; updated label & chooser)* | Opens a 4-option chooser dialog (**Feature / Idea**, **Bug report (device info only)**, **Device / Install compatibility**, **Blank / Other**). **Never** dumps traces/NDJSON/crash logs into the URL. **Never** overwrites the clipboard silently. | Browser launch |
| **Upload Log & Bug Report** | `settings_diag_paste_issue` *(existing ID; fixed payload)* | Shows redaction preview dialog → uploads full sanitized Markdown report to `dpaste.org` (fallback `paste.rs`) → copies the short Paste URL → opens `issues/new?template=bug_report.yml` with **ONLY** the Paste URL + compact 8-line Environment block (zero raw log duplication!). | Explicit HTTPS paste + browser |

---

## 2. Requirements Specification

- **`REQ-ISS-01` (Standalone Copy Log):** Settings → Diagnostics **must** provide a dedicated **Copy log** control (`settings_diag_copy_log`) that copies the full sanitized Markdown report to the clipboard without launching a browser.
- **`REQ-ISS-02` (No Hardcoded Bug Title on General Issue Creation):** Tapping **Create GitHub Issue** **must not** force `"Bug report: on-device processing / performance issue"` for non-bug submissions.
- **`REQ-ISS-03` (Issue Type Chooser):** Tapping **Create GitHub Issue** **must** present a localized chooser with four options:
  1. **Idea / Feature request**
  2. **Bug report (device info only)**
  3. **Device / Install compatibility**
  4. **Blank / Other issue**
- **`REQ-ISS-04` (Clean Idea / Feature Payload):** Selecting **Idea / Feature request** **must** open `https://github.com/NotCherry/LFM3Pocket/issues/new?template=feature_request.yml` with **no** logs, traces, NDJSON, or crash dumps (at most optional `app_version=<versionName>` query parameter matching the YAML form field ID).
- **`REQ-ISS-05` (Device-Info-Only Bug Payload):** Selecting **Bug report (device info only)** **must** open `issues/new?template=bug_report.yml` pre-filling **only** the compact Environment summary (App version, Device manufacturer/model, Android release + SDK, ABIs, RAM/thermal snapshot, active model basename & projector flag) — **strictly zero** processing traces, NDJSON events, or crash dumps.
- **`REQ-ISS-06` (Device Compatibility Payload):** Selecting **Device / Install compatibility** **must** open `issues/new?template=device_compatibility.yml` pre-filling device model, Android version/SDK, supported ABIs, memory page size (if readable), and app version.
- **`REQ-ISS-07` (Blank / Other Issue Payload):** Selecting **Blank / Other issue** **must** open `https://github.com/NotCherry/LFM3Pocket/issues/new` with an empty query string (no pre-filled title or body).
- **`REQ-ISS-08` (De-Duplicated Paste + Issue Payload):** **Upload Log (Paste) & Bug Report** **must** include ONLY the uploaded HTTPS Paste URL and the compact Environment summary in the GitHub issue URL—**never** duplicating the full raw diagnostics report inside `?body=` or form query parameters.
- **`REQ-ISS-09` (URL Length Budget & Secret Redaction):** Every generated GitHub Issue URL **must** stay under 1,500 characters (well below mobile browser / GitHub App limits), redact secrets (`Bearer ...`, `token=...`, `api_key=...`), and strip absolute filesystem paths to basenames only.
- **`REQ-ISS-10` (Repository Issue Forms):** Add `.github/ISSUE_TEMPLATE/bug_report.yml`, `feature_request.yml`, `device_compatibility.yml`, and `config.yml` (`blank_issues_enabled: true`) so in-app deep links populate structured GitHub Issue Form fields cleanly.
- **`REQ-ISS-11` (Discoverability from About Screen):** Add a subtle **"Send feedback / Create GitHub Issue"** link in `StillwaterAbout` (`ComposeHostActivity.kt`) using the same lightweight issue chooser so users with ideas or feedback do not have to scroll to Settings → Crash Log.
- **`REQ-ISS-12` (EN + PL Localization & Resource ID Stability):** Provide idiomatic English and Polish strings for all new/updated labels and chooser entries while preserving existing view IDs (`settings_crash_log_*`, `settings_diag_*`).

---

## 3. Exact URL & Repository Issue Template Specifications

### 3.1 Compact Environment Summary Format (~8 lines)
Used by **Bug report (device info only)**, **Device compatibility**, and **Upload Log & Bug Report** (never traces/NDJSON/crash dumps):

```markdown
- App version: 0.20.4
- Device: Xiaomi 24129PN74G (Android 15, SDK 35)
- ABIs: arm64-v8a
- RAM: 4120 MB free / 12288 MB total (app RSS 840 MB, lowMemory=false)
- Thermal: none
- Model: LFM3-2B-Q4_K_M.gguf (projector=none)
- Inference limits: ctx=4096, threads=4, maxTokens=512, promptLookup=true
```

### 3.2 URL Mapping Table (`GitHubIssueUrlBuilder.kt`)

| `IssueKind` | Target URL Pattern | Fields Populated |
| --- | --- | --- |
| `FEATURE_IDEA` | `.../issues/new?template=feature_request.yml&app_version=<ver>` | `app_version` only (empty body, zero logs) |
| `BUG_DEVICE_INFO` | `.../issues/new?template=bug_report.yml&environment=<compactEnv>` | `environment` only (zero traces/NDJSON/crash log) |
| `BUG_WITH_PASTE` | `.../issues/new?template=bug_report.yml&environment=<compactEnv>&diagnostics_url=<pasteUrl>` | `environment` + `diagnostics_url` (zero duplicated raw log) |
| `DEVICE_COMPATIBILITY` | `.../issues/new?template=device_compatibility.yml&device_info=<compactDevice>` | `device_info` (device, Android release/SDK, ABIs, app version) |
| `BLANK` | `https://github.com/NotCherry/LFM3Pocket/issues/new` | None (completely empty) |

### 3.3 Repository `.github/ISSUE_TEMPLATE/` Blueprint

1. **`.github/ISSUE_TEMPLATE/config.yml`**:
   - `blank_issues_enabled: true`
   - Optional link to `docs/ANDROID_COMPATIBILITY.md` for Xiaomi / Android 15–17 install help.
2. **`.github/ISSUE_TEMPLATE/feature_request.yml`**:
   - `name`: `Idea / Feature request`
   - `labels`: `["enhancement"]`
   - Fields: `summary` (textarea — what idea or improvement?), `use_case` (textarea — why does it help?), `app_version` (input — optional app version, pre-fillable via `?app_version=`).
3. **`.github/ISSUE_TEMPLATE/bug_report.yml`**:
   - `name`: `Bug report`
   - `labels`: `["bug"]`
   - Fields: `what_happened` (textarea), `steps` (textarea), `environment` (textarea — pre-fillable via `?environment=`), `diagnostics_url` (input — optional Paste URL or pasted snippet, pre-fillable via `?diagnostics_url=`).
4. **`.github/ISSUE_TEMPLATE/device_compatibility.yml`**:
   - `name`: `Device / Install compatibility report`
   - `labels`: `["android", "needs-device-test"]`
   - Fields: `device_info` (textarea — pre-fillable via `?device_info=`), `oem_skin` (input — e.g. HyperOS 2/3/4, OneUI, Pixel), `install_channel` (dropdown — GitHub Release APK, Obtainium, F-Droid, ADB), `symptom` (textarea — exact error message or `adb install` output).

---

## 4. Localized Copy Table (EN / PL)

| String Key | English (`values/strings.xml`) | Polish (`values-pl/strings.xml`) |
| --- | --- | --- |
| `settings_crash_log_heading` | `DIAGNOSTICS & FEEDBACK` *(or keep `DIAGNOSTICS & LOGS`)* | `DIAGNOSTYKA I ZGŁOSZENIA` |
| `settings_diag_copy_log` | `Copy log` | `Kopiuj log` |
| `settings_diag_copied` | `Copied sanitized diagnostics log to clipboard.` | `Skopiowano zanonimizowany log diagnostyczny do schowka.` |
| `settings_diag_github_issue` | `Create GitHub Issue` | `Utwórz zgłoszenie na GitHub` |
| `settings_diag_paste_issue` | `Upload Log (Paste) & Bug` | `Wyślij log (Paste) i zgłoś błąd` |
| `settings_diag_issue_picker_title` | `Create GitHub Issue` | `Utwórz zgłoszenie na GitHub` |
| `settings_diag_issue_type_idea` | `Idea / Feature request (clean template)` | `Pomysł / Propozycja funkcji (czysty szablon)` |
| `settings_diag_issue_type_bug` | `Bug report (device info only)` | `Zgłoszenie błędu (tylko dane urządzenia)` |
| `settings_diag_issue_type_device` | `Device / Install compatibility` | `Kompatybilność urządzenia / instalacji` |
| `settings_diag_issue_type_blank` | `Blank issue (empty)` | `Puste zgłoszenie (bez szablonu)` |
| `settings_diag_github_opened` | `Opened GitHub Issue form in browser.` | `Otwarto formularz zgłoszenia GitHub w przeglądarce.` |

---

## 5. Phased Implementation Plan

### Phase 1 — Repository Issue Templates & Kotlin URL Builder
1. Create `.github/ISSUE_TEMPLATE/{config,bug_report,feature_request,device_compatibility}.yml`.
2. Add `app/src/main/java/com/notcherry/lfm3pocket/GitHubIssueUrlBuilder.kt` (Kotlin per [ADR 0027](../adr/0027-kotlin-first-adoption-and-incremental-migration.md)) with `@JvmStatic` entry points and `IssueKind` enum (`FEATURE_IDEA`, `BUG_DEVICE_INFO`, `BUG_WITH_PASTE`, `DEVICE_COMPATIBILITY`, `BLANK`).
3. Add `DiagnosticsReportBuilder.buildCompactEnvironment(DeviceInfo)` (or migrate report formatting cleanly alongside `GitHubIssueUrlBuilder.kt`), preserving `redactSecrets()` and `basenameOnly()`.
4. Keep `DiagnosticsReportBuilder.buildGitHubIssueUrl(...)` delegating cleanly (or update `DiagnosticsAndTelemetryTest` + add `GitHubIssueUrlBuilderTest.kt`).

### Phase 2 — Settings Diagnostics & About UI Wiring
1. Update `CrashLogSettingsSection.java`:
   - Add standalone **Copy log** button (`R.id.settings_diag_copy_log`) next to View / Export.
   - Change **Create GitHub Issue** (`R.id.settings_diag_github_issue`) to show the 4-option `AlertDialog` chooser via `StillwaterUi.showDialog(...)`, launching `GitHubIssueUrlBuilder.buildUrl(...)` without touching the clipboard or dumping logs.
   - Fix **Upload Log (Paste) & Bug** (`R.id.settings_diag_paste_issue`) to call `GitHubIssueUrlBuilder.buildBugWithPasteUrl(deviceInfo, uploadedUrl)` so the raw 4,800-char log is **never** duplicated into the URL.
2. Add EN & PL strings in `app/src/main/res/values/strings.xml` and `values-pl/strings.xml`, and declare `settings_diag_copy_log` in `res/values/ids.xml`.
3. Add a lightweight **"Create GitHub Issue / Feedback"** button on `StillwaterAbout` (`ComposeHostActivity.kt`) opening the same issue-type chooser.

### Phase 3 — Verification & Docs Sync
1. Update `docs/DIAGNOSTICS.md` to reflect the shipped UX.
2. Run JVM unit tests (`:app:testSmokeDebugUnitTest -Phanami.skipNative=true`) and Settings E2E tests.

---

## 6. Verification & Acceptance Criteria

| ID | Criterion | Verification Method |
| --- | --- | --- |
| **`AC-ISS-01`** | `FEATURE_IDEA` URL uses `template=feature_request.yml`, contains zero trace/NDJSON/crash text, and contains no hardcoded `"Bug report"` title. | `GitHubIssueUrlBuilderTest.kt` |
| **`AC-ISS-02`** | `BUG_DEVICE_INFO` URL uses `template=bug_report.yml`, includes sanitized compact device info only, and excludes processing traces, NDJSON, and crash logs. | `GitHubIssueUrlBuilderTest.kt` |
| **`AC-ISS-03`** | `BUG_WITH_PASTE` URL includes the Paste URL and compact environment summary only, staying `< 1,500` chars and never duplicating the raw Markdown report. | `GitHubIssueUrlBuilderTest.kt` |
| **`AC-ISS-04`** | `BLANK` URL equals exact `https://github.com/NotCherry/LFM3Pocket/issues/new` with no query string. | `GitHubIssueUrlBuilderTest.kt` |
| **`AC-ISS-05`** | Secret redaction (`Bearer`, `token=`, `api_key=`) and path basename stripping hold for all URL kinds. | `GitHubIssueUrlBuilderTest.kt` |
| **`AC-ISS-06`** | Tapping **Copy log** copies full sanitized Markdown report to clipboard without firing an `ACTION_VIEW` browser intent. | Instrumentation / UI test. |
| **`AC-ISS-07`** | Tapping **Create GitHub Issue** opens the 4-option chooser and never silently overwrites clipboard. | Instrumentation / UI test. |
| **`AC-ISS-08`** | All `.github/ISSUE_TEMPLATE/*.yml` files validate cleanly as GitHub Issue Forms YAML with matching query-parameter IDs (`app_version`, `environment`, `diagnostics_url`, `device_info`). | YAML schema lint + manual GitHub preview. |
