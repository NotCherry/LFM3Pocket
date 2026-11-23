# Test timeouts

Hung or deadlocked tests must not burn an entire CI job. Limits are layered: Gradle task timeouts (whole suite), JUnit 4 rules or `@Test(timeout=…)` (per test method), and workflow scripts (emulator E2E).

## Gradle (`app/build.gradle`)

| Task | Mechanism | Timeout | Rationale |
| --- | --- | --- | --- |
| JVM unit (`*UnitTest*`, Gradle `Test` type) | `tasks.withType(Test)` | 10 minutes | JVM job in `.github/workflows/build-apk.yml` fails fast around 12 minutes with jstack; Gradle kills the task first. |
| Connected instrumentation (`connected*AndroidTest`) | `tasks.matching { … }` on `DefaultTask` | 48 minutes | `DeviceProviderInstrumentTestTask` is **not** a `Test` subtype. This mainly protects **local** `connectedSmokeDebugAndroidTest` runs. On CI, `.github/scripts/run-jni-smoke.sh` already wraps Gradle with `timeout 30m` (smoke) or `45m` (deep). |

To raise limits, edit the blocks at the bottom of `app/build.gradle` and keep shell/workflow timeouts aligned.

## JVM unit tests (JUnit 4)

Shared constants live in `app/src/test/java/com/notcherry/lfm3pocket/TestTimeouts.java`:

- **`CONCURRENCY_DEFAULT`** (30 seconds, `withLookingForStuckThread(true)`) — applied via `HanamiConcurrencyUnitTest` for voice session, loopback API, and voice preempt tests. The rule runs the test body on a **worker thread** (good for deadlock dumps; **not** for direct `RuntimePolicy` calls on the test thread).
- **`VOICE_UNLOAD_LIFECYCLE_MS`** (90 seconds) — `@Test(timeout=…)` on `VoiceUnloadLifecycleTest` (same test thread; loops several timed unload paths).
- **`RUNTIME_POLICY_INTERLEAVING_MS`** (30 seconds) — `@Test(timeout=…)` on the serialized interleaving test in `RuntimePolicyTest`.

`TestTimeoutsSelfTest` applies `SELF_CHECK_RULE` to a hanging `Statement` (no `@Rule` / no `expected=`) to prove the timeout fires in CI.

## Instrumented tests (`androidTest`)

On-device UI tests must stay on the **instrumentation thread**. JUnit 4 `Timeout` rules run the test on another thread and are **not** used for emulator E2E.

Per-method ceilings rely on:

1. **CI shell** — `run-jni-smoke.sh` (`timeout 30m` smoke / `45m` deep).
2. **Gradle** — `connected*AndroidTest` task timeout (48m, mostly for local runs).
3. **Occasional `@Test(timeout=…)`** where already present (e.g. `PromptEncodingNativeTest`).

Intentionally long smoke/deep classes (`GenerationSmokeTest`, `GenerationDeepSmokeTest`, matrix E2E) depend on the suite-level limits above, not per-class rules.

## Raising a limit

1. **JVM concurrency** — bump `TestTimeouts` or extend `HanamiConcurrencyUnitTest`; never put a class-level `Timeout` rule on `RuntimePolicy` unit tests that call the policy on the test thread.
2. **Voice unload loops** — raise `VOICE_UNLOAD_LIFECYCLE_MS`.
3. **Connected suite** — bump `run-jni-smoke.sh`, the workflow job `timeout-minutes`, and optionally the `connected*AndroidTest` Gradle task together.
4. Document permanent changes in the test class Javadoc or an ADR.

The JVM workflow jstack loop in `build-apk.yml` remains a second line of defence after Gradle and per-test limits.
