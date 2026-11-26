# Plan & Requirements: Kotlin-First Adoption and Incremental Java Migration

- **Status:** Active (Wave 1 implemented: `AndroidPermissionGate.kt`, `GitHubIssueUrlBuilder.kt`, and Kotlin unit tests)
- **Date:** 2026-11-26
- **Governing ADR:** [ADR 0027 — Kotlin-first adoption for new modules and incremental Java migration](../adr/0027-kotlin-first-adoption-and-incremental-migration.md)
- **Related ADRs:** [ADR 0024](../adr/0024-settings-section-controllers.md), [ADR 0026](../adr/0026-android-12-to-17-compatibility-and-permission-gates.md), [ADR 0028](../adr/0028-separate-diagnostics-export-from-github-issues.md)

---

## 1. Baseline Codebase Audit (Measured 2026-11-26)

| Source Set | `.java` Files | `.java` Lines | `.kt` Files | `.kt` Lines | Kotlin Share (Lines) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app/src/main/java` | 198 | 28,474 | 21 | 4,433 | **13.5%** |
| `app/src/test` | 101 | 10,170 | 1 | 53 | **0.5%** |
| `app/src/androidTest` | 47 | 5,931 | 2 | 308 | **4.9%** |
| `app/src/androidTestDeviceCheck` | 1 | 122 | 0 | 0 | **0.0%** |
| **Total (Java + Kotlin)** | **347** | **44,697** | **24** | **4,794** | **9.7%** |

*Non-JVM layers: 3 `.cpp` (2,098 lines), 3 `.h` (231 lines), 1 `.rs` (77 lines), 26 `.xml` (1,441 lines).*

**Key conclusion:** Kotlin is currently **9.7%** of Java+Kotlin code (13.5% in `main`), far below 30%, even though Kotlin `2.2.20`, Compose compiler, KSP, and `kotlinx-serialization` are already active in every build. There is no architectural reason to cap Kotlin to Compose UI alone—and no justification for a disruptive big-bang rewrite.

---

## 2. Requirements Specification

- **`REQ-KT-01` (Kotlin Default for New Code):** All new Android UI, Settings section controllers, permission/compatibility modules, diagnostics/issue builders, state models, and new JVM/Compose tests **must** be authored in Kotlin unless an explicit exception applies (JNI bridge or trivial addition to an existing Java file).
- **`REQ-KT-02` (Zero Big-Bang Rewrites):** Existing stable Java files **must not** be converted to Kotlin purely for style; migrations happen only when a module is already being substantially refactored or redesigned.
- **`REQ-KT-03` (Clean Behavior-Preserving Diffs):** When migrating an existing Java class to Kotlin, separate mechanical Java→Kotlin conversion from functional changes (separate commit or PR) whenever the class exceeds ~150 lines.
- **`REQ-KT-04` (Idiomatic Java Interop):** Every Kotlin class/object called from Java **must** expose Java-friendly signatures (`@JvmStatic`, `@JvmOverloads`, `@JvmField` as appropriate) and avoid leaking Kotlin-only types (`UInt`, `inline reified`, bare `suspend`) across Java boundaries.
- **`REQ-KT-05` (Boundary Nullability):** Java interfaces/helpers newly consumed by Kotlin modules **should** annotate nullable parameters and return values (`@Nullable` / `@NonNull` or Kotlin-safe wrappers) to eliminate silent platform-type NPEs.
- **`REQ-KT-06` (Lean Runtime & F-Droid Compatibility):** `kotlin-reflect` **must not** be added to `app/build.gradle` runtime dependencies; APK shrinking (`minifyEnabled true`, `shrinkResources true`) and F-Droid buildability **must** remain green.
- **`REQ-KT-07` (API 35+ Collection Lint Safety):** Because `compileSdk >= 35` and `minSdk 26`, Kotlin code **must not** call JDK 21/API 35 `MutableList.removeFirst()` / `removeLast()` (use `removeAt(0)` / `removeAt(lastIndex)`), verified via Android Lint `NewApi` checks.
- **`REQ-KT-08` (CI Guard Compatibility):** All new/migrated Kotlin UI files and tests **must** remain compatible with `.github/scripts/check-ui-tokens.py` (no ad-hoc hex colors) and `.github/scripts/assert-android-test-results.py` (test inventory discovery).

---

## 3. Module Classification Matrix

| Category | Modules / Areas | Policy | Rationale |
| --- | --- | --- | --- |
| **Tier 1 — Kotlin-First (New Work)** | `AndroidPermissionGate.kt`, `GitHubIssueUrlBuilder.kt`, new Settings sections (ADR 0024), new Compose screens & dialogs, new JVM/UI tests | **Write in Kotlin** | Highest leverage for sealed states, exhaustive `when`, null safety, and concise tests. |
| **Tier 2 — Opportunistic Migration (When Redesigned)** | `CrashLogSettingsSection`, `DiagnosticsReportBuilder`, remaining `MainActivity` extracted coordinators, Settings sections undergoing UI overhaul | **Migrate when touched significantly** | High churn; benefits immediately from Kotlin data classes and null safety. |
| **Tier 3 — Defer / Keep in Java** | `NativeBridge`, `CactusWhistleBridge`, `TinyVocaPhonemizerBridge` (JNI bridges); `HistoryDatabase` + Room entities/DAOs (while `room.generateKotlin=false`); stable tested domain classes (`ToolRegistry`, `ChatSession`, `LocalDiagnostics`, `ModelRuntime`) | **Stay in Java** | Low churn or sensitive JNI/Room KSP signatures; high risk-to-reward ratio if converted prematurely. |

---

## 4. Phased Rollout Plan (Tied to Active Roadmap)

1. **Wave 1 (Immediate — Paired with Android 12–17 & Issues Plans):**
   - Implement `AndroidPermissionGate.kt` + `AndroidPermissionGateTest.kt` in Kotlin ([ADR 0026](../adr/0026-android-12-to-17-compatibility-and-permission-gates.md)).
   - Implement `GitHubIssueUrlBuilder.kt` + `GitHubIssueUrlBuilderTest.kt` in Kotlin ([ADR 0028](../adr/0028-separate-diagnostics-export-from-github-issues.md)).
2. **Wave 2 (Settings & UI Evolution):**
   - Author new Settings sections and Compose dialogs (e.g., About feedback entry point, issue chooser, permission rationale sheets) in Kotlin, keeping View-based `SettingsUiFactory` interop clean.
   - Migrate `CrashLogSettingsSection` to Kotlin if redesigned beyond a minor edit.
3. **Wave 3 (Test Suite Modernization & Optional Room Flip):**
   - Default all new unit tests (`app/src/test`) and Compose/UI tests (`app/src/androidTest`) to Kotlin.
   - Only consider flipping `room.generateKotlin=true` in a dedicated persistence PR once `HistoryDatabase` and DAOs are ready to migrate together with full `app/schemas/*.json` migration tests.

---

## 5. Verification & Acceptance Criteria

| ID | Criterion | Verification Method |
| --- | --- | --- |
| **`AC-KT-01`** | All Wave 1 new modules (`AndroidPermissionGate.kt`, `GitHubIssueUrlBuilder.kt`) compile cleanly with Kotlin 2.2.20 / JVM 17 and expose ergonomic `@JvmStatic` APIs to Java callers. | `:app:testSmokeDebugUnitTest` + code review. |
| **`AC-KT-02`** | `kotlin-reflect` is absent from runtime classpath / APK dependencies. | `./gradlew :app:dependencies --configuration phoneReleaseRuntimeClasspath`. |
| **`AC-KT-03`** | `.github/scripts/assert-android-test-results.py --check-inventory` discovers all new `.kt` instrumentation tests cleanly. | CI unit job. |
| **`AC-KT-04`** | `.github/scripts/check-ui-tokens.py` passes on all new/modified `.kt` UI files. | CI unit job. |
| **`AC-KT-05`** | Release APK builds cleanly with `minifyEnabled true` and `shrinkResources true` without new ProGuard/R8 crashes at Java↔Kotlin boundaries. | `:app:assemblePhoneRelease` + smoke verification. |
| **`AC-KT-06`** | Zero `NewApi` lint errors from Kotlin stdlib/collection extensions (e.g. `removeFirst`/`removeLast`) on `minSdk 26`. | `:app:lintSmokeDebug` check. |
