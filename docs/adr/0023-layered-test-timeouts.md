# ADR 0023 — Layered test timeouts

- Status: Accepted
- Date: 2026-11-23
- Related: [#273](https://github.com/NotCherry/LFM3Pocket/pull/273), [#265](https://github.com/NotCherry/LFM3Pocket/issues/265), [ADR 0012](0012-two-tier-emulator-e2e-gating.md), [ADR 0016](0016-runtime-policy-owner-thread.md), [`TESTING.md`](../TESTING.md)

## Context

A hung or deadlocked test can hold a CI job until the runner kills it. The hangs that matter are concurrency, voice, and runtime-policy tests on the JVM, and native or emulator tests that wait on a device. A workflow kill arrives late. It often names neither the test nor the stuck thread, and a local Gradle run had no bound at all.

[#265](https://github.com/NotCherry/LFM3Pocket/issues/265) asked for a suite ceiling and a per-test ceiling after a voice-session deadlock burned a JVM job. [#273](https://github.com/NotCherry/LFM3Pocket/pull/273) landed that policy. [ADR 0012](0012-two-tier-emulator-e2e-gating.md) already runs emulator end-to-end work as a pull-request smoke tier and a deep tier. Both tiers, and the JVM unit suite, need a bound that fails the run before the job budget is gone.

Ceilings, class names, and where to edit them stay in [`TESTING.md`](../TESTING.md).

## Decision

Every test suite has a bounded runtime. The bound is layered, and an inner layer is set to fire before the layer outside it, except on a CI connected run. There the shell process timeout is the effective bound, and the Gradle connected timeout only guards local runs.

What a failure carries depends on the layer. A per-test failure names the test. A stuck-thread dump comes only from the shared concurrency rule. A per-method ceiling fails the test with a timeout exception and does not look up the stuck thread. A CI connected kill by the process timeout does not name the hung test or dump threads.

1. **Per-test limits** where a hang is likely and the test can leave the runner thread. Concurrency-sensitive JVM tests share one JUnit 4 timeout rule that looks for a stuck thread. Long-running tests that are safe to move off the runner thread, including some native tests, take an explicit higher per-method ceiling. On-device UI tests stay on the instrumentation thread, so they do not use that rule. Intentionally long emulator end-to-end classes are bounded by the suite and workflow layers, not by a per-class rule.
2. **Suite-level build timeout.** The JVM unit task and each connected instrumentation task have a Gradle task timeout. On CI the emulator script also wraps the connected run in a process timeout. That process timeout fires before the Gradle connected timeout, so it is the bound for the CI connected suite. The Gradle connected timeout is what stops a local connected run. The process-timeout kill does not name the hung test or dump threads. The leftover record is the device log, a screenshot, an activity dump, and any instrumentation result already written.
3. **Workflow timeouts are the outermost layer.** Existing job timeouts stay. On the JVM job, the thread-dump watchdog sits outside the Gradle suite timeout and inside the job timeout. These are the outer line of defence. They fire only when an inner limit did not.

The timeout mechanism itself is covered by a self-test in the normal JVM suite. The self-test uses a short timeout that does not look for a stuck thread. A hanging test body must be interrupted, and the failure must be the timeout. A green JVM suite is evidence that the JUnit timeout mechanism still interrupts a hang. It does not exercise the shared rule's stuck-thread lookup.

Raising a limit means keeping the layers aligned. On the JVM, the per-test ceiling stays inside the suite timeout, and the suite timeout stays inside the watchdog and the workflow timeout. On a CI connected run the shell process timeout stays the effective suite bound and stays inside the workflow timeout. The Gradle connected timeout stays the local guard. [`TESTING.md`](../TESTING.md) is the list of which knobs move together.

## Consequences

- A deadlock in a JVM test covered by the shared concurrency rule fails that test with a stuck-thread diagnostic. A per-method ceiling fails the test with a timeout exception and does not look up the stuck thread. A JVM suite that never returns is killed by the build before the workflow spends its full budget.
- Local runs are bounded as well as CI. A hang no longer depends on a workflow being present.
- Both emulator tiers from [ADR 0012](0012-two-tier-emulator-e2e-gating.md) stay inside a workflow timeout, with the smoke and deep process timeouts inside that job. On CI those process timeouts are the effective bound. They do not name the hung test or dump threads. The leftover record is the device log, a screenshot, an activity dump, and any instrumentation result already written.
- JUnit 4 runs a timed test body on a separate thread. A test that relies on the runner thread's identity cannot use a per-test timeout. Runtime-policy tests that call the policy on the calling thread are that case: the owner is the thread that invokes it ([ADR 0016](0016-runtime-policy-owner-thread.md)). Those methods stay without a per-method ceiling unless the work is posted onto the owner. The suite timeout still caps them.
- On-device UI tests have the same thread constraint. They stay on the instrumentation thread and rely on the suite and workflow layers.
- Limits need maintenance as suites grow. A ceiling that was comfortable becomes a false failure if it is not raised with the suite. On the JVM, raising an inner limit past the layer outside it lets the outer kill win, and a test under the shared concurrency rule loses its stuck-thread diagnostic. On a CI connected run the shell is already the effective bound, so the Gradle connected timeout is not the layer that fires.
- The self-test proves the JUnit timeout mechanism interrupts a hang. It does not exercise the shared rule's stuck-thread lookup, and it does not prove that every suite's ceiling is still inside the layer outside it.

## Alternatives considered

- **Rely only on CI workflow timeouts and the thread-dump watchdog.** This was the previous outer defence, and it stays. Rejected as the only bound: the job dies late, the failure often does not name the hung test, and a local run has no watchdog ([#265](https://github.com/NotCherry/LFM3Pocket/issues/265)).
- **A global per-test default for every test.** Rejected. The JUnit timeout moves the body off the runner thread. That breaks tests that need the runner thread's identity, including runtime-policy calls on the owner thread and on-device UI tests that must stay on the instrumentation thread. The shared rule is applied only where that move is safe. Everything else keeps the suite and workflow bounds.
