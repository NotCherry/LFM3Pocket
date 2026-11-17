# ADR 0008: CI on the cherry-tree runner

## Status

Accepted

## Context

Stage 1 CI (unit tests, the emulator smoke test, and the phone APK) spends a long time on GitHub-hosted runners. The owner has a machine, CHERRY-TREE, that can be registered on this public repository with the labels `self-hosted` and `cherry-tree`.

A self-hosted runner on a public repository executes workflow code. Fork pull requests use the workflow file from the fork. The runner's home directory and tool cache persist between jobs.

## Decision

`build-apk.yml` starts with `pick-runner` on `ubuntu-latest`. The unit, emulator, and phone-APK jobs use CHERRY-TREE only when a runner labeled `cherry-tree` is online and not busy. Otherwise they use `ubuntu-latest`.

Fork pull requests, a missing `RUNNER_STATUS_TOKEN`, and a failed runners API call use `ubuntu-latest`.

`release.yml` always runs on `ubuntu-latest`. That job has `contents: write`, can push a tag, and publishes the APK. It does not share CHERRY-TREE's SDK directory or tool cache with pull-request jobs.

The emulator job changes `/dev/kvm` permissions only on GitHub-hosted runners. Those machines are discarded after the job. On CHERRY-TREE the runner user must already be able to open `/dev/kvm`. The job does not install a udev rule there.

## Consequences

The three build jobs in one workflow share one decision. On a single machine they run one after another. `timeout-minutes` does not include time spent waiting for a runner. If CHERRY-TREE goes offline after `pick-runner` has selected it, GitHub can leave the jobs queued until the 24 hour queue limit. A busy runner would wait the same way, so a busy runner falls back to `ubuntu-latest`.

The fork check inside `pick-runner` is defense in depth. A fork can replace the workflow and request the `cherry-tree` labels directly. The repository setting **Require approval for all outside collaborators** is what keeps that workflow from being scheduled. Read the workflow diff before approving a fork run.

## Alternatives

- Hosted runners only. That leaves the owner's machine out of CI. Rejected while CHERRY-TREE is available to run jobs that find it idle.
- Use CHERRY-TREE whenever it is online, including when it is busy. Rejected because a queued job can wait up to 24 hours, and `timeout-minutes` does not cover that wait.
- A separate label, or an ephemeral runner (`--ephemeral`), for the release APK. A clean disk per job would also isolate pull-request state from release builds. CHERRY-TREE is not registered that way. Release stays on `ubuntu-latest` until an ephemeral runner is registered for that purpose.
- Send one job to CHERRY-TREE and leave the other two on hosted runners, so a single machine does not run the workflow serially. Possible later if serial execution on CHERRY-TREE is slower than three hosted runners.
