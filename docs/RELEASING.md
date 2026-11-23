# Releasing Hanami

Hanami is the app name. It runs Liquid AI LFM3 models. The GitHub repository is still `NotCherry/LFM3Pocket`. This document covers signed GitHub releases, Obtainium, IzzyOnDroid, and a main F-Droid submission. It does not create or store a signing key.

## Version from the tag

Every non-docs push to `main` creates the next `vMAJOR.MINOR.PATCH` tag and publishes a GitHub Release. Manual tags still work. `ReleaseVersion` sets:

- `versionName` = `MAJOR.MINOR.PATCH` (the leading `v` is dropped)
- `versionCode` = `MAJOR * 1000000 + MINOR * 1000 + PATCH`

Examples: `v0.1.0` → `0.1.0` / `1000`, `v1.2.3` → `1.2.3` / `1002003`. A later semver tag always has a higher `versionCode`. Tags such as `v1.2.3-rc.1` fail the build on purpose.

### Automatic bump on `main`

`.github/scripts/next-hanami-version.py` looks at commits since the latest `v*` tag:

| Signal | Bump |
| --- | --- |
| No `v*` tag yet | Always `v0.1.0` |
| Default | patch (`v0.1.0` → `v0.1.1`) |
| `feat:` / `feat(scope):` | minor |
| `type!:` or a `BREAKING CHANGE:` footer | major |
| `[release patch\|minor\|major]` in a subject | that bump (overrides conventional commits in the same range) |
| `release-as: vX.Y.Z` on its own line | that exact tag |

Docs-only merges (`docs/**` and `*.md`) do not start the release workflow. The tag is created with `GITHUB_TOKEN` inside the same workflow that builds the APK, so the tag push does not start a second run.

Untagged builds, including local debug builds, use `0.1.0` / `1000`. The Java/Kotlin package stays `com.notcherry.lfm3pocket`. The install id is `dev.notcherry.hanami`.

### Existing installs

Android will not upgrade `com.notcherry.lfm3pocket` to `dev.notcherry.hanami`. They are different apps. The old app keeps its private models and history until it is uninstalled. Data does not move. Users who want only Hanami install the new APK and remove the old app themselves.

## GitHub Actions signing secrets

The workflow `.github/workflows/release.yml` signs the tag build. Create these repository secrets (Settings → Secrets and variables → Actions). Do not commit a keystore, a password, or a base64 key.

| Secret | Contents |
| --- | --- |
| `HANAMI_KEYSTORE_BASE64` | Base64 of the release keystore (JKS or PKCS12). `base64 -w0 hanami-release.keystore` |
| `HANAMI_STORE_PASSWORD` | Keystore password |
| `HANAMI_KEY_ALIAS` | Key alias inside that keystore |
| `HANAMI_KEY_PASSWORD` | Password for that alias |

Generate the key on a machine you control, for example:

```bash
keytool -genkeypair -v -keystore hanami-release.keystore -alias hanami \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep a backup of the keystore outside the repository. Losing it means every later GitHub APK is a different signer, and Android will refuse to upgrade. The workflow decodes the secret into a temp file and passes it as `HANAMI_KEYSTORE_FILE`.

If any of the four secrets is missing, the auto-release (and a versioned tag build) still publishes. The APK is **unsigned**, the release body says so, and the Actions job stays green so `main` is not red for missing keys. Do not create signing keys in this repository. F-Droid's buildserver also builds unsigned and signs with its own key.

## What the release workflow publishes

On each auto-tagged `main` push (and on a manual `v*` tag):

1. Checks out the pinned llama.cpp revision from `vendor/LLAMA_CPP_COMMIT` and builds the `phone` release flavor (`arm64-v8a` only), native code included.
2. Sets `versionName` / `versionCode` from the tag.
3. Signs the APK when the four secrets are present and checks the signature with `apksigner`. Otherwise attaches an unsigned APK and notes that in the release body.
4. Writes a changelog from commit subjects since the previous `vMAJOR.MINOR.PATCH` tag (or the latest 30 subjects if there is no previous tag).
5. Publishes a GitHub Release. The APK is `Hanami-v<versionName>-arm64-v8a.apk`, plus a `.sha256` file.

`workflow_dispatch` without a tag still rebuilds the rolling `latest` debug APK. That path is manual so a normal `main` merge does not build twice.

Obtainium matches that name stably: the only changing piece is the version. Ignore pre-releases. Asset filter:

```text
Hanami-v.*-arm64-v8a\.apk
```

The rolling `latest` pre-release is still a debug APK named `Hanami-latest-arm64-debug.apk`. It does not match that filter.

```bash
git tag v0.1.0
git push origin v0.1.0
```

Fastlane changelog files live at `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Add the next file in the commit you tag (`1000.txt` is `v0.1.0`). The GitHub Release notes are generated from git history and are separate from that file.

## Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app → GitHub → `NotCherry/LFM3Pocket`.
3. Turn off "Include prereleases" if you only want signed tags.
4. Set the APK filter regex to `Hanami-v.*-arm64-v8a\.apk`.
5. The package shown after install is `dev.notcherry.hanami`.

If the GitHub repository is renamed, edit the Obtainium source URL. GitHub redirects old release URLs, but Obtainium stores the owner/name you entered.

## IzzyOnDroid

IzzyOnDroid redistributes the signed GitHub Release APK. It does not rebuild from source, so the signature matches Obtainium and GitHub.

1. Publish at least one signed `v*` release first.
2. Confirm the app is MIT-licensed, has no tracking, and the APK does not embed model weights.
3. Open an inclusion request at the [IzzyOnDroid repo](https://gitlab.com/IzzyOnDroid/repo) (issue or merge request). Point at `https://github.com/NotCherry/LFM3Pocket/releases` and the asset pattern `Hanami-v<version>-arm64-v8a.apk`.
4. Give them `dev.notcherry.hanami`, the MIT license, and a note that LFM3 names the models, not the app.
5. Keep the same signing key. A new key looks like a different app to their updater.

## F-Droid

Main F-Droid rebuilds from source and signs with its own key. A phone that has the GitHub-signed Hanami cannot upgrade to the F-Droid build, or the reverse, without uninstalling. That is separate from the applicationId change above.

Draft metadata: [`docs/fdroid/dev.notcherry.hanami.yml`](fdroid/dev.notcherry.hanami.yml). Localized text and changelogs: `fastlane/metadata/android/en-US/`.

1. Tag the commit you want built. Replace `PLACEHOLDER_FIRST_HANAMI_TAG` in the draft with that tag. Set `versionName` / `versionCode` with the formula above. The placeholder is not a real commit.
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) and add `metadata/dev.notcherry.hanami.yml` from the draft.
3. Fastlane files in this repo are picked up from the tagged source. `fastlane/metadata/android/en-US/images/phoneScreenshots/` has two Stillwater frames (home and about) drawn with the app's fonts and colors. They are placeholders: on this machine the API 30 emulator ran the app (UI Automator read "Hanami" and "About offline mode") but every framebuffer capture was black, including guest-GPU and windowed runs. Replace those PNGs with phone captures before an F-Droid submission.
4. Open a merge request. The inclusion policy is [f-droid.org/docs/Inclusion_Policy](https://f-droid.org/docs/Inclusion_Policy/). Expect a reviewer to adjust `prebuild` / `sudo` to the current buildserver image. The first native build can take a long time (`timeout` in the draft is 3 hours).
5. `AutoUpdateMode` and `UpdateCheckMode` are `None` because `versionCode` is not a literal in `build.gradle`. A tag scanner that only greps the file would publish the untagged fallback. After the recipe is green, a maintainer can add builds by hand, or teach the check to apply `major*1000000+minor*1000+patch`.

The `phone` flavor is the one F-Droid should build. It is `arm64-v8a` only. `smoke` is the x86_64 emulator flavor and `deviceCheck` is a side-by-side probe id (`dev.notcherry.hanami.devicecheck`).

### License

`LICENSE` is MIT. The copyright line still says "LFM3 Pocket contributors". That is the existing license, not a new one. Bundled Inter, JetBrains Mono, and Noto Serif Display are SIL OFL 1.1 (`licenses/fonts/`). llama.cpp `v0.8.0` is MIT. Model weights are not shipped and stay under Liquid AI's model license.

### Dependency audit

Runtime libraries are AndroidX (CameraX, Room, DataStore, Compose, Navigation, Activity), Kotlin, and kotlinx-serialization. JUnit and UI Automator are test-only. There is no Firebase, no Google Play Services client, and no Play Billing. The `google()` Maven repository is used to resolve AndroidX. The CI emulator image `google_apis` is a test runner image, not an app dependency.

Native code is llama.cpp, built with `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_CPU_KLEIDIAI=OFF`, `LLAMA_CURL=OFF`, and `GGML_BACKEND_DL=OFF`. On `arm64-v8a`, CMake sets `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` (same preferred CPU ladder step as PocketPal’s llama.rn `v8_2_dotprod` variant) and appends `-O3` for Debug APKs without wiping NDK flags or forcing `-DNDEBUG` (see `docs/adr/0001-arm64-dotprod-and-kv-residency.md`). The only prebuilt binary found in the pinned llama.cpp tree is `examples/llama.android/gradle/wrapper/gradle-wrapper.jar`. The app does not build that example. The fdroiddata draft deletes it before the scan.

### Reproducible native build

These are the reasons a GitHub APK and an F-Droid APK will not be byte-for-byte identical, even before the different signature:

- llama.cpp is not vendored. The build fetches the commit pinned in `vendor/LLAMA_CPP_COMMIT` (currently `8642541b255bfb5dc39cd351a178be09013a55d6`, from `v0.8.0`) via `scripts/fetch-llama.sh`. A moved tag cannot change the binary. F-Droid should keep that checkout.
- Cactus Whistle STT runtime is not vendored. Release and phone ARM64 CI run `scripts/fetch-cactus.sh` (pin `vendor/CACTUS_RELEASE`, checksums `vendor/CACTUS_ANDROID_ARM64.sha256`) and set `HANAMI_REQUIRE_CACTUS=ON`. The packaged APK is checked for linked `cactus_load` / `cactus_transcribe` symbols via `.github/scripts/assert-cactus-runtime-in-apk.py`.
- NDK `27.2.12479018` (r27c) and CMake `3.22.1` are pinned. A different NDK produces different codegen.
- `GGML_NATIVE=OFF` is required. Leaving it on enables `-march=native` and the APK only runs on the builder's CPU.
- Release `.so` files still embed a GNU build-id and the builder's absolute paths in debug info unless `-Wl,--build-id=none` and `-ffile-prefix-map` are added. This project does not add those flags yet, so native objects are not reproducible across machines.
- `useLegacyPackaging true` stores JNI uncompressed with ZIP timestamps. AGP's ZIP alignment and `SOURCE_DATE_EPOCH` still have to match.
- F-Droid signs with its key. Signature blocks alone make the APK bytes differ from the GitHub artifact. Compare unsigned APKs if you chase reproducibility.

No custom launcher icon is checked in. The system default icon is used until one is added.
