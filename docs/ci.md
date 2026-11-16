# CI runners

`.github/workflows/build-apk.yml` (pull requests and pushes to `main`) and `.github/workflows/release.yml` (release builds) start with a `pick-runner` job. That job always runs on GitHub-hosted `ubuntu-latest`. Every build job then uses:

```yaml
runs-on: ${{ fromJSON(needs.pick-runner.outputs.runs-on) }}
```

`pick-runner` calls `GET /repos/{owner}/{repo}/actions/runners` and writes one JSON `runs-on` value.

| Situation | `runs-on` |
| --- | --- |
| Pull request from a fork, or a pull request whose head repository is missing | `ubuntu-latest` |
| Any other event whose head repository is present and is not this repository | `ubuntu-latest` |
| `RUNNER_STATUS_TOKEN` missing, blank, or rejected by the API | `ubuntu-latest` |
| A runner labeled `cherry-tree` has `status` `online` | `["self-hosted","cherry-tree"]` |
| No `cherry-tree` runner is online | `ubuntu-latest` |

GitHub assigns a free runner when several online `cherry-tree` runners exist. An online runner that is already busy stays eligible, and the job waits for a matching runner. The label match is exact: `cherry-tree`. The machine name can be `CHERRY-TREE`.

Push events, tags, `workflow_dispatch`, and pull requests opened from branches in this repository may use the self-hosted runner. The selection rules are checked by `.github/scripts/pick-runner.py --self-test` in the unit-test job.

## What CHERRY-TREE needs

Register the runner on this repository (Settings → Actions → Runners → New self-hosted runner) and add the extra label `cherry-tree`. The runner agent adds `self-hosted` itself. Run it as a service so the machine stays `online` while it is up.

The workflows install JDK, Gradle, Python, and the Android SDK on the runner during the job. Leave `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset, or point them at an SDK directory the runner user can write. `android-actions/setup-android` reuses that SDK when it is set, and otherwise installs command-line tools under the runner user's home (`~/.android/sdk`). Later jobs on the same machine reuse that directory.

Have these in place before the runner takes a job:

- A current Linux x86_64 GitHub Actions runner. `android-actions/setup-android@v4` is a Node 24 action. The emulator job boots an x86_64 API 30 image.
- `git`, `curl`, `tar`, and `unzip`. `actions/checkout` needs `git`. The setup actions unpack downloads with `tar` and `unzip`.
- For the emulator job, `/dev/kvm` that the runner user can read and write. When the device exists and is not accessible yet, the job uses passwordless `sudo` to install a udev rule. The emulator job needs KVM on whichever runner it lands on.
- Free disk for Android platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1, the Gradle cache, and an emulator system image.
- Outbound HTTPS to `github.com`, `api.github.com`, `dl.google.com`, and the Maven repositories Gradle uses.

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

This repository is public. A pull request from a fork runs the workflow file from that fork. The `pick-runner` job in this repository's workflow forces `ubuntu-latest` for that event. A replaced workflow can still ask for `runs-on: [self-hosted, cherry-tree]` directly.

In Settings → Actions → General, set **Fork pull request workflows from outside collaborators** to **Require approval for all outside collaborators**. A fork workflow is not scheduled until a maintainer approves that run. Read the workflow diff before approving.

These workflows run on `pull_request`, `push`, and `workflow_dispatch`. A `pull_request_target` workflow runs with this repository's secrets; checking out the fork head there would execute fork code on the runner. Keep CI on the events above.
