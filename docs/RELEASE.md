# Stage 1 release build and verification

This document is the reproducible APK / model-import / test-gate recipe for LFM3 Pocket Stage 1 (R03). It consolidates what a clean checkout must do and what CI does **not** prove.

## Clean checkout → phone APK

1. Install JDK 17, Android SDK + NDK, and Gradle 8.13.
2. Clone this repository.
3. Check out the pinned llama.cpp tree (not bundled in git):

```bash
git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
```

4. Build the ARM64 debug phone APK:

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

Workflow **Publish GitHub Release** (`.github/workflows/release.yml`):

| Trigger | Release | Asset |
| --- | --- | --- |
| Green **Android Stage 1 CI** on `main` | Pre-release tag `latest` (rolling) | `LFM3Pocket-latest-arm64-debug.apk` |
| Push tag `v*` (e.g. `v0.1.0`) | Versioned release | `LFM3Pocket-0.1.0-arm64-debug.apk` |
| Manual **workflow_dispatch** | Rebuild `latest`, or publish a `v*` tag if supplied | same as above |

Action run artifacts still expire; use the [Releases](https://github.com/notcherry/lfm3pocket/releases) page for durable downloads.

Publish a numbered release from a clean `main`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

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
- No telemetry; diagnostics stay on-device (`docs/DIAGNOSTICS.md`, `files/diagnostics.ndjson`).
- Persistent AI memory / reminders are Stage 2 — Stage 1 history is local transcripts only.

## License notices

| Component | License |
| --- | --- |
| LFM3 Pocket application source | MIT (`LICENSE`) |
| llama.cpp (pinned `v0.8.0`) | MIT (upstream) |
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
