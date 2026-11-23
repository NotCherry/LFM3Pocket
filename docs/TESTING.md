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

- **`CONCURRENCY_DEFAULT`** (30 seconds, `withLookingForStuckThread(true)`) — applied via `HanamiConcurrencyUnitTest` for voice session, loopback API, and voice preempt tests. The rule runs the test body on a **worker thread** (good for deadlock dumps; **not** for direct `RuntimePolicy` calls that assume the JVM test runner’s starting thread).
- **`VOICE_UNLOAD_LIFECYCLE_MS`** (90 seconds) — `@Test(timeout=…)` on `VoiceUnloadLifecycleTest`. In JUnit 4 this **also** runs the test body on a separate timer thread (like the `Timeout` rule), not the thread that started the test. That is acceptable here because the tests drive `VoiceActionRuntime` through its own worker and timed waits.
- **`RUNTIME_POLICY_INTERLEAVING_MS`** (30 seconds) — `@Test(timeout=…)` only on `serializedInterleavingsKeepForegroundAndWakeEffectsConsistent`. Same separate-thread behaviour as above; this method is safe because every `RuntimePolicy` mutation goes through the dedicated **policy-owner** executor, not the JUnit timer thread.

`TestTimeoutsSelfTest` applies `SELF_CHECK_RULE` to a hanging `Statement` (no `@Rule` / no `expected=`) to prove the timeout fires in CI.

## Instrumented tests (`androidTest`)

On-device UI tests must stay on the **instrumentation thread**. JUnit 4 `Timeout` rules run the test on another thread and are **not** used for emulator E2E.

Per-method ceilings rely on:

1. **CI shell** — `run-jni-smoke.sh` (`timeout 30m` smoke / `45m` deep).
2. **Gradle** — `connected*AndroidTest` task timeout (48m, mostly for local runs).
3. **Occasional `@Test(timeout=…)`** where already present (e.g. `PromptEncodingNativeTest`).

Intentionally long smoke/deep classes (`GenerationSmokeTest`, `GenerationDeepSmokeTest`, matrix E2E) depend on the suite-level limits above, not per-class rules.

## Raising a limit

1. **JVM concurrency** — bump `TestTimeouts` or extend `HanamiConcurrencyUnitTest`. Do not use a class-level `Timeout` rule or `@Test(timeout=…)` on `RuntimePolicyTest` methods that invoke `RuntimePolicy` directly on the calling thread; use an owner executor (as the interleaving test does) or leave those methods without a per-method wall clock (Gradle still caps the suite).
2. **Voice unload loops** — raise `VOICE_UNLOAD_LIFECYCLE_MS`.
3. **Connected suite** — bump `run-jni-smoke.sh`, the workflow job `timeout-minutes`, and optionally the `connected*AndroidTest` Gradle task together.
4. Document permanent changes in the test class Javadoc or an ADR.

The JVM workflow jstack loop in `build-apk.yml` remains a second line of defence after Gradle and per-test limits.
