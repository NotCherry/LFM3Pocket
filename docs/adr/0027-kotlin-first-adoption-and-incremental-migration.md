# ADR 0027 — Kotlin-first adoption for new modules and incremental Java migration

- Status: Accepted
- Date: 2026-11-26
- Related: [ADR 0024](0024-settings-section-controllers.md), [ADR 0026](0026-android-12-to-17-compatibility-and-permission-gates.md), [ADR 0028](0028-separate-diagnostics-export-from-github-issues.md), [`kotlin-adoption-plan.md`](../plans/kotlin-adoption-plan.md)

## Context

Hanami started as a minimal Java + JNI proof-of-concept and later adopted Kotlin (`2.2.20`), Jetpack Compose, KSP, Room, and DataStore for the Stillwater UI shell (`ComposeHostActivity`, `HistoryActivity`, `ChatPanel`, `ChatTurnList`, `ModelManagerPanel`, `ui/theme/*`) and settings stores (`InferenceSettingsStore`, `ImageSettingsStore`).

A recurring question during architecture planning is whether Kotlin is "too small a slice" of the repository (e.g., <30%) and should therefore stay confined to Compose UI, or whether the codebase should deliberately expand Kotlin usage.

Measured codebase distribution on `main` (2026-11-26):

| Source set | Java files (lines) | Kotlin files (lines) | Kotlin share (lines) | Kotlin share (files) |
| --- | --- | --- | --- | --- |
| `app/src/main/java` | 198 (28,474) | 21 (4,433) | **13.5%** | 9.6% |
| `app/src/test` | 101 (10,170) | 1 (53) | **0.5%** | 1.0% |
| `app/src/androidTest` | 47 (5,931) | 2 (308) | **4.9%** | 4.1% |
| **Total (`app/src`, Java+Kotlin)** | **347 (44,697)** | **24 (4,794)** | **9.7%** | **6.5%** |

*(Native layers — 3 `.cpp`, 3 `.h`, 1 `.rs` — account for ~2,406 lines separately.)*

Key observations from these numbers:

1. **The Kotlin toolchain cost is already fully paid.** `build.gradle` and `app/build.gradle` already apply `org.jetbrains.kotlin.android` (`2.2.20`), `org.jetbrains.kotlin.plugin.compose`, `com.google.devtools.ksp`, and `kotlinx-serialization-bom:1.8.1`. Every build already runs `kotlinc` and KSP.
2. **Restricting Kotlin to Compose UI wastes that investment.** At 9.7% overall (well below 30%), keeping all new non-Compose logic in Java forces verbose boilerplate, manual null checks, ad-hoc enums/POJOs, and error-prone `if/else` chains for SDK-versioned permission gates, state snapshots, and issue/report builders—the exact domains where Kotlin's null safety, `sealed interface`, `data class`, exhaustive `when`, and expression-bodied pure functions shine.
3. **A big-bang Java-to-Kotlin rewrite is counterproductive.** The existing 347 Java files are battle-tested (101 JVM unit test files + 47 instrumentation suites), tightly coupled to JNI (`NativeBridge`, `CactusWhistleBridge`, `TinyVocaPhonemizerBridge`), and actively receiving feature PRs. Mass conversion would create noisy diffs, merge conflicts, and regression risk with zero user-facing value.
4. **Build/tooling constraints are manageable:**
   - `.github/scripts/assert-android-test-results.py` already parses Kotlin class/object declarations for test inventory verification.
   - `.github/scripts/check-ui-tokens.py` already scans both `.kt` and `.java` files for ad-hoc hex colors.
   - `app/build.gradle` currently sets `room.generateKotlin=false` because Room entities/DAOs are Java; this works seamlessly with Kotlin callers and should only flip if/when the Room layer is migrated together.
   - F-Droid and offline builds already support our Kotlin 2.2.20 stack (no proprietary plugins, no `kotlin-reflect` needed).

## Decision

Hanami adopts a **Kotlin-first policy for new code with opportunistic, incremental Java migration** — with **no artificial repository percentage cap** and **no big-bang rewrite**.

1. **Kotlin by default for all new modules and new tests.**
   - New Android/platform compatibility modules (e.g., `AndroidPermissionGate.kt` from [ADR 0026](0026-android-12-to-17-compatibility-and-permission-gates.md)), new diagnostics/issue builders (e.g., `GitHubIssueUrlBuilder.kt` from [ADR 0028](0028-separate-diagnostics-export-from-github-issues.md)), new Settings section controllers ([ADR 0024](0024-settings-section-controllers.md)), new Compose screens, and new JVM unit / Compose UI tests are written in **Kotlin** by default.
2. **Opportunistic migration of high-churn Java classes ("Boy Scout / strangler" rule).**
   - When a Java class is being substantially redesigned or split (such as extracting remaining `MainActivity` coordinators, rewriting `CrashLogSettingsSection`, or overhauling `DiagnosticsReportBuilder`), migrating that unit to Kotlin in its own clean PR (or cleanly separated commit) is encouraged.
   - Small, localized bug fixes in existing Java files stay in Java so review diffs stay minimal and easy to audit.
3. **Explicit "stay in Java" boundaries (until deliberately redesigned).**
   - **JNI bridge classes** (`NativeBridge`, `CactusWhistleBridge`, `TinyVocaPhonemizerBridge`): keep in Java to avoid subtle JNI signature / companion-object / `@JvmStatic` mismatches with `native.cpp` and `hanami_dizione`.
   - **Room schema & entities** (`HistoryDatabase`, entities/DAOs): keep in Java while `room.generateKotlin=false` remains set, unless a dedicated Room migration PR converts the whole persistence slice and verifies all `app/schemas/*.json` migrations.
   - **Stable, low-churn pure Java domain classes** (`ToolRegistry`, `ChatSession`, `LocalDiagnostics`, etc.): do not convert for cosmetics alone.
4. **Java–Kotlin interoperability discipline.**
   - Kotlin APIs called from Java must annotate entry points with `@JvmStatic`, `@JvmField`, or `@JvmOverloads` where appropriate so Java call sites stay idiomatic (no `.INSTANCE.` noise).
   - Avoid exposing Kotlin-only constructs (`inline` reified functions, unsigned types, `suspend` functions without a callback/blocking adapter) across active Java call boundaries.
   - Annotate shared Java boundary methods with `@Nullable` / `@NonNull` (`androidx.annotation`) when new Kotlin callers consume them, preventing platform-type NPEs.
   - Keep `kotlin-reflect` **out** of runtime dependencies so R8/ProGuard shrinking and APK size stay lean.
   - Keep `compileOptions` and `kotlinOptions` aligned on JVM 17, and enforce Android Lint `NewApi` checks on Kotlin collections (specifically guarding against JDK 21 / API 35 `List.removeFirst()` / `List.removeLast()` static resolution traps on `minSdk 26`).

## Consequences

- **Positive:**
  - Kotlin share will grow naturally from ~10% toward a majority over time in the highest-churn areas (UI, Settings sections, permission gates, state models, tests) without stalling feature delivery.
  - Compile-time nullability, sealed state machines, and exhaustive `when` expressions reduce runtime crashes—particularly for Android 12–17 SDK branching and permission state handling.
  - Unlocks idiomatic Jetpack Compose and `androidx.activity` / `OnBackInvokedCallback` / Material 3 adoption without awkward Java wrappers.
- **Negative / Risks:**
  - Mixed Java/Kotlin codebase persists during the multi-quarter transition; contributors must follow the interop checklist so Java callers do not suffer ergonomic regressions.
  - Platform types (`T!`) at Java→Kotlin call sites can hide nulls unless Java contracts are annotated or wrapped defensively.
  - Converting a Java file and modifying its behavior in the same commit obscures git blame; conversions must preserve behavior in a dedicated commit or PR whenever practical.

## Alternatives considered

- **Cap Kotlin strictly to Compose UI / Theme (~10–30% scope).** Rejected. The repository already pays the full Gradle/Kotlin/KSP compile cost. Artificial language segregation forces new domain logic (like versioned Android 12–17 permission gates and issue template builders) into verbose Java and splits state models awkwardly across two languages.
- **Full repository Java-to-Kotlin rewrite now.** Rejected. Converting ~45,000 lines of tested Java in one sweep would stall product work, risk breaking JNI/Room/E2E contracts, and produce unreviewable diffs.
- **Keep everything new in Java and deprecate Kotlin outside Compose.** Rejected. Modern AndroidX, Compose, Navigation, and Android 15–17 APIs are increasingly Kotlin-first; retreating to Java increases friction and maintenance cost.
