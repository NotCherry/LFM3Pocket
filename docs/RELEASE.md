# Stage 1 release build and verification

This document is the reproducible APK / model-import / test-gate recipe for Hanami (the app formerly described here as LFM3 Pocket) Stage 1 (R03). It consolidates what a clean checkout must do and what CI does **not** prove. Signed tags, Obtainium, IzzyOnDroid, and F-Droid are in [`RELEASING.md`](RELEASING.md). The app runs Liquid AI LFM3 models. LFM3 is not the product name.

## Clean checkout → phone APK

1. Install JDK 17, Android SDK + NDK, and Gradle 8.13.
2. Clone this repository.
3. Check out the pinned llama.cpp tree (not bundled in git). The pin is the commit named in `vendor/LLAMA_CPP_COMMIT` (a `v0.8.0` revision; the file is the source of truth, not the branch tip):

```bash
bash scripts/fetch-llama.sh
```

4. **Optional tiny-voca dizione JNI** — English tiny-voca TTS uses `libhanami_dizione.so`, built from pinned voca sources when Rust and the NDK are available (`bash scripts/fetch-voca.sh`, then `scripts/build-dizione-jni.sh`). By default Gradle does **not** require it (`lintRelease`, F-Droid `assemblePhoneRelease`, and local debug keep working without Rust). GitHub release and Stage 1 CI set `HANAMI_REQUIRE_DIZIONE=1` or `-Phanami.requireDizione=1`; opt out with `-Phanami.requireDizione=0`. CI checks the packaged phone APK with `.github/scripts/assert-dizione-jni-in-apk.py` (rolling `latest` pre-release included).

5. Build the ARM64 debug phone APK:

```bash
gradle :app:assemblePhoneDebug --no-daemon
```

The APK does **not** contain GGUF weights or projectors. Import official Liquid AI LFM3 / LFM3-Omni files through the in-app document picker into private app storage.

### Flavors

| Flavor | ABI | Purpose |
| --- | --- | --- |
| `phone` | `arm64-v8a` | Device APK artifact |
| `smoke` | `x86_64` | Emulator JNI + UI E2E |
| `deviceCheck` | `arm64-v8a` | Optional real Omni probe instrumentation |

## CI artifacts and gates

GitHub Actions workflow **Android Stage 1 CI**:

- **JVM unit contracts** — `gradle :app:testSmokeDebugUnitTest`
- **Android emulator JNI + UI E2E** — API 30 x86_64 emulator; Stories15M smoke GGUF + EMU-FAKE UI journeys
- **Build ARM64 debug APK** — uploads `LFM3Pocket-arm64-debug`

Pinned smoke fixture SHA-256 and host provisioning are documented in `docs/TEST_PLAN.md`. Failures upload instrumentation XML/HTML and logcat.

## GitHub Releases (downloadable APK)

Workflow **Publish GitHub Release** (`.github/workflows/release.yml`) triggers directly on every non-doc merge/push to `main` (as well as manual `v*` tag pushes and `workflow_dispatch`) without waiting on the `deep` emulator E2E suite in **Android Stage 1 CI**. Before creating a version tag or publishing any asset, the release workflow runs JVM unit tests (`:app:testSmokeDebugUnitTest`), builds the ARM64 release APK, and verifies dizione JNI, Cactus Whistle runtime, and `apksigner` signature integrity so a failed build never consumes a version number. After publishing, `.github/scripts/prune-old-releases.py` automatically prunes older GitHub Releases so only the newest 5 published releases remain (git tags are kept intact so semantic version numbering stays monotonic). Tag releases are signed when repository signing secrets are configured and are described in [`RELEASING.md`](RELEASING.md).

| Trigger | Release behavior | Asset |
| --- | --- | --- |
| Non-doc push to `main` | Run unit tests + build & verify release APK, auto-bump/create a `v*` tag, publish versioned release (marked as latest), and prune to newest 5 releases | `Hanami-v<version>-arm64-v8a.apk` |
| Push tag `v*` | Run unit tests + build & verify release APK, publish versioned release for that tagged commit SHA, and prune to newest 5 releases | `Hanami-v<version>-arm64-v8a.apk` |
| Manual **workflow_dispatch** | Rebuild rolling pre-release `latest` debug when no tag is supplied, or publish/rebuild the supplied `v*` tag, then prune to newest 5 releases | `Hanami-latest-arm64-debug.apk` or `Hanami-v<version>-arm64-v8a.apk` |

Notes:
- Versioned releases create/update the numbered `v*` release (not the rolling `latest` pre-release). `workflow_dispatch` without a tag rebuilds the rolling `latest` debug pre-release.
- `v0.1.0` sets versionName `0.1.0` and versionCode `1000`. CI run artifacts expire after 3 days (debug/device-check APKs upload only on `main` pushes and manual dispatches; test logs upload on failure); use the [Releases](https://github.com/NotCherry/LFM3Pocket/releases) page for downloadable release APKs (latest 5 retained).

## Model import expectations

- Text LFM3: import one quantized GGUF.
- Omni multimodal: import matching `LFM3-Omni-2B-*.gguf` **and** `mmproj-LFM3-Omni-2B-*.gguf` from the same publisher revision.
- Recommended phone RAM: ≥ 4 GB, preferably 6+ GB.
- Pairing / size / dimension preflight rejects mismatched or oversized inputs before native decode.

## Device support and known limitations

**Supported intent:** offline-first Android phones that can load the selected GGUF in process RAM.

**Explicit Stage 1 limitations:**

- Physical ARM64 Omni quality (text / ASR / vision / speech generation / tools) is **not** closed by emulator CI. See `docs/ARM64_OMNI_TEST_PROTOCOL.md` and `docs/OMNI_EMULATOR_RESULTS.md`.
- Emulator CameraX / fake-mic journeys prove app wiring, not model visual or speech quality.
- Spoken response playback (V05) and the full voice journey E2E (V06) may still be PARTIAL/MISSING in the acceptance matrix when this document ships.
- Optional SearXNG web search is user-configured HTTPS only; core chat must work offline with search disabled.
- No telemetry; diagnostics stay on-device (`docs/DIAGNOSTICS.md`, `files/diagnostics.ndjson`, crash log in [ADR 0004](adr/0004-local-crash-log.md)).
- Local notification reminders are inexact and on-device ([ADR 0007](adr/0007-voice-reminders-workmanager.md)). Conversation history is local transcripts, with an explicit user-visible compact summary. There is no automatic long-term memory and no cloud memory.

## License notices

| Component | License |
| --- | --- |
| Hanami application source | MIT (`LICENSE`) |
| llama.cpp (commit pinned in `vendor/LLAMA_CPP_COMMIT`, from `v0.8.0`) | MIT (upstream) |
| Liquid AI LFM3 / LFM3-Omni GGUF weights | Liquid AI model license (not redistributed in the APK) |

Publishers must keep model license notices with any redistributed weights; this project never embeds those weights in CI APKs.

## Verification limitations (read before claiming “done”)

A green CI run proves:

- unit contracts
- emulator JNI lifecycle on Stories15M
- EMU-FAKE UI journeys (including offline privacy, voice journey, diagnostics stress)
- ARM64 APK packaging

It does **not** prove:

- real Omni comprehension on a physical phone
- Polish ASR accuracy on human speech
- audible Omni TTS quality
- physical flashlight / tool side effects (#84)

Track those under the Stage 1 acceptance matrix (`docs/ACCEPTANCE_MATRIX.md`) and tracker issue #4. R04 records CI evidence and names the ARM64 release blockers; it does not flip AT rows to VERIFIED.

## Atlas UI epic (separate from AT VERIFIED gates)

The Atlas redesign (tracker [#135](https://github.com/NotCherry/LFM3Pocket/issues/135), workstream `U` / UT-01..UT-08) covers theme, structured chat rendering, Home/Settings/History chrome, and E2E selector adaptation. It is **orthogonal** to Stage 1 AT-01..AT-09 VERIFIED rules:

| Doc | Role |
| --- | --- |
| [`DESIGN.md`](DESIGN.md) | Atlas style guide (tokens, components, anti-patterns) |
| [`UI_ACCEPTANCE_MATRIX.md`](UI_ACCEPTANCE_MATRIX.md) | UT-01..UT-08 evidence map (JVM / EMU-FAKE / device visual) |
| [`UI_E2E_CONTRACT.md`](UI_E2E_CONTRACT.md) | Stable chat IDs + flat transcript + Settings navigation for automators |
| `device-evidence/reports/ui-atlas/` | Device visual checklist + operator report (U16) |

Green UT rows or Atlas screenshots **never** flip AT-01..AT-09 to VERIFIED. Stage 1 product capability remains governed by `ACCEPTANCE_MATRIX.md` and tracker #4.
