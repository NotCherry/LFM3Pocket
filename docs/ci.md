# CI runners

`.github/workflows/build-apk.yml` (pull requests and pushes to `main`) starts with a `pick-runner` job. That job always runs on GitHub-hosted `ubuntu-latest`. The unit, emulator, and phone-APK jobs then use:

```yaml
runs-on: ${{ fromJSON(needs.pick-runner.outputs.runs-on) }}
```

`pick-runner` calls `GET /repos/{owner}/{repo}/actions/runners` and writes one JSON `runs-on` value.

| Situation | `runs-on` |
| --- | --- |
| Pull request from a fork, or a pull request whose head repository is missing | `ubuntu-latest` |
| Any other event whose head repository is present and is not this repository | `ubuntu-latest` |
| `RUNNER_STATUS_TOKEN` missing, blank, or rejected by the API | `ubuntu-latest` |
| A runner labeled `cherry-tree` is `online` and not `busy` | `["self-hosted","cherry-tree"]` |
| Every online `cherry-tree` runner is `busy` | `ubuntu-latest` |
| No `cherry-tree` runner is online | `ubuntu-latest` |

The label match is exact: `cherry-tree`. The machine name can be `CHERRY-TREE`. When several matching runners are online and idle, GitHub assigns one of them.

`timeout-minutes` starts when a runner picks up the job. It does not include time spent waiting to be assigned. GitHub cancels a job that is still queued after 24 hours. A busy runner therefore falls back to `ubuntu-latest` instead of entering that queue. The check and the assignment are still separate: if CHERRY-TREE goes offline after `pick-runner` has selected it, the build jobs wait on those labels until the machine returns or the 24 hour queue limit expires. The three build jobs share one decision, so on a single machine they run one after another.

`.github/workflows/release.yml` always uses `ubuntu-latest`. That job can push a tag and it publishes the APK. It does not build on the disk that pull-request jobs leave behind on CHERRY-TREE. The reason is recorded in [`docs/adr/0008-self-hosted-ci-runner.md`](adr/0008-self-hosted-ci-runner.md).

Pushes to `main`, `workflow_dispatch` of the Stage 1 workflow, and pull requests opened from branches in this repository may use the self-hosted runner. Tag builds and `release.yml` stay on `ubuntu-latest`. The selection rules are checked by `.github/scripts/pick-runner.py --self-test` in the unit-test job.

## What CHERRY-TREE needs

Register the runner on this repository (Settings → Actions → Runners → New self-hosted runner) and add the extra label `cherry-tree`. The runner agent adds `self-hosted` itself. Run it as a service so the machine stays `online` while it is up.

The workflows install JDK, Gradle, Python, and the Android SDK on the runner during the job. Leave `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset, or point them at an SDK directory the runner user can write. `android-actions/setup-android` reuses that SDK when it is set, and otherwise installs command-line tools under the runner user's home (`~/.android/sdk`). Later jobs on the same machine reuse that directory.

Have these in place before the runner takes a job:

- A current Linux x86_64 GitHub Actions runner. `android-actions/setup-android@v4` is a Node 24 action. The emulator job boots an x86_64 API 30 image.
- `git`, `curl`, `tar`, and `unzip`. `actions/checkout` needs `git`. The setup actions unpack downloads with `tar` and `unzip`.
- For the emulator job, `/dev/kvm` that the runner user can already read and write. Add that user to the `kvm` group and restart the runner service. The job does not install udev rules on this machine. On a GitHub-hosted runner, which is thrown away after the job, the same step may install a `MODE="0666"` udev rule when `/dev/kvm` is not yet writable.
- Free disk for Android platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1, the Gradle cache, and an emulator system image.
- Outbound HTTPS to `github.com`, `api.github.com`, `dl.google.com`, and the Maven repositories Gradle uses.
- A glibc new enough for the CPython builds `actions/setup-python` downloads (a current Ubuntu or Debian). Those builds are the ones GitHub publishes for hosted Ubuntu runners. Alpine and other musl systems cannot install that Python.

The job installs the build tools:

| Tool | Provided by | Used for |
| --- | --- | --- |
| Temurin JDK 17 | `actions/setup-java` | Gradle and `sdkmanager` |
| Gradle 8.13, including the Gradle user-home cache | `gradle/actions/setup-gradle` | Build and unit tests |
| Android cmdline-tools, platform-tools, `platforms;android-35`, `build-tools;35.0.0` | `android-actions/setup-android` | Every Android job |
| NDK `27.2.12479018` and CMake `3.22.1` | the same action, via `sdkmanager` | Phone, emulator, and release APKs |
| Android emulator and the API 30 `google_apis` x86_64 image | `android-actions/setup-android` and `reactivecircus/android-emulator-runner` | JNI and UI smoke tests |
| Python 3.12 | `actions/setup-python` | CI scripts |

`gradle/actions/setup-gradle` caches the Gradle user home between runs. Leave the `cache` input on `actions/setup-java` unset so that user home is stored once.

## Create `RUNNER_STATUS_TOKEN`

`GITHUB_TOKEN` cannot list self-hosted runners. Listing them requires a fine-grained personal access token with **Administration: Read** on this repository.

1. Open GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.
2. Resource owner: the account that owns this repository. Repository access: only this repository.
3. Repository permissions: **Administration: Read-only**. Leave every other permission at No access.
4. Generate the token and copy it. It starts with `github_pat_`.
5. In this repository, open Settings → Secrets and variables → Actions → New repository secret. Name it `RUNNER_STATUS_TOKEN` and paste the token.

A missing secret or an API error selects `ubuntu-latest`, and the workflow continues. The token's repository permission is Administration: Read, which covers runner names, labels, and online status. Keep the value only in that Actions secret.

Fork pull requests do not receive repository secrets. The workflow also ignores the token for those events.

## Public fork pull requests

This repository is public. A pull request from a fork runs the workflow file from that fork, including `pick-runner.yml`. The fork check in that file forces `ubuntu-latest` for the normal workflow. It is defense in depth. A replaced workflow can delete the check and ask for `runs-on: [self-hosted, cherry-tree]` directly.

In Settings → Actions → General, set **Fork pull request workflows from outside collaborators** to **Require approval for all outside collaborators**. A fork workflow is not scheduled until a maintainer approves that run. Read the workflow diff before approving.

These workflows run on `pull_request`, `push`, and `workflow_dispatch`. A `pull_request_target` workflow runs with this repository's secrets; checking out the fork head there would execute fork code on the runner. Keep CI on the events above.
