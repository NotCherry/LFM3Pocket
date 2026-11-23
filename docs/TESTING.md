# Test timeouts

Hung or deadlocked tests must not burn an entire CI job. Limits are layered: Gradle task timeouts (whole suite), JUnit 4 `Timeout` rules (per test method), and workflow scripts (emulator E2E).

## Gradle (`app/build.gradle`)

`tasks.withType(Test)` sets a wall clock per Gradle test task:

| Task name pattern | Timeout | Rationale |
| --- | --- | --- |
| `*UnitTest*` | 10 minutes | JVM job in `.github/workflows/build-apk.yml` fails fast around 12 minutes with jstack; Gradle kills the task first. |
| `*AndroidTest*` | 48 minutes | Connected smoke/deep tiers use `timeout 30m` / `45m` in `.github/scripts/run-jni-smoke.sh`; Gradle allows headroom above the shell wrapper. |

To raise a task limit, edit the `configureEach` block at the bottom of `app/build.gradle` and keep the workflow shell timeout at or below the Gradle value.

## JVM unit tests (JUnit 4)

Shared rules live in `app/src/test/java/com/notcherry/lfm3pocket/TestTimeouts.java`:

- **`TestTimeouts.UNIT_DEFAULT`** (2 minutes) — apply via `HanamiUnitTestBase` or an explicit `@Rule` on new tests.
- **`TestTimeouts.CONCURRENCY_DEFAULT`** (30 seconds, `withLookingForStuckThread(true)`) — voice session, unload lifecycle, runtime policy, loopback API, and related deadlock-sensitive suites via `HanamiConcurrencyUnitTest` or a field rule.

`TestTimeoutsSelfTest` sleeps past the 2-second self-check rule to prove timeouts fire in CI.

## Instrumented tests (`androidTest`)

Per-method ceilings are in `InstrumentedTestTimeouts.java`:

- **`DEFAULT`** — 5 minutes for typical emulator UI/contract tests (apply when adding new `@Test` methods that might hang).
- **`HEAVY_JNI`** — 12 minutes on `GenerationSmokeTest` and `GenerationDeepSmokeTest`.
- **`HEAVY_E2E`** — 15 minutes on `TextReadableAtScaleE2eTest`, `DiagnosticsStressE2eTest`, and similar stress paths.
- **`CONCURRENCY`** — `LoopbackAgentApiInstrumentationTest`.

Long-running PR smoke / deep E2E classes keep explicit higher `@Rule` values so intentionally slow coverage is not cut off.

## Raising a limit

1. Prefer a **per-class `@Rule`** with a named constant in `TestTimeouts` or `InstrumentedTestTimeouts` over disabling timeouts.
2. If an entire suite is legitimately slow, bump the matching **Gradle task** timeout and the **workflow** step (`timeout-minutes` / `run-jni-smoke.sh`) together.
3. Document the reason in the test class Javadoc or ADR if the change is permanent.

The JVM workflow jstack loop in `build-apk.yml` remains a second line of defence after Gradle and JUnit limits.