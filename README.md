# Hanami

On-device Android chat for [Liquid AI](https://www.liquid.ai/) LFM3 models, using native [llama.cpp](https://github.com/ggml-org/llama.cpp). Hanami is the app. LFM3 is the model family it runs. Text inference stays on the phone. Optional web search uses a SearXNG endpoint configured by the user.

The source repository is still named **LFM3Pocket**. Renaming the GitHub repository is a separate owner action. This change does not rename it. GitHub will redirect the old URL after a rename, but do that before submitting to F-Droid if you want the source URL in the metadata to stay stable.

The install id is `dev.notcherry.hanami`. An existing install of `com.notcherry.lfm3pocket` will not upgrade in place. Android treats the new id as a different app, so the old app and its private models stay until you uninstall them. The two apps can be installed side by side.

## Download

Installable ARM64 APKs are published on the [Releases](https://github.com/NotCherry/LFM3Pocket/releases) page:

- Numbered **`v*`** tags build a signed `Hanami-v<version>-arm64-v8a.apk` (see [docs/RELEASING.md](docs/RELEASING.md))
- **Latest CI build** (pre-release) — debug APK refreshed after each green `main` CI run, not the Obtainium asset

Model weights are never bundled in the APK. Import LFM3 / LFM3-Omni GGUFs in the app after install.

## Build

GitHub Actions builds the ARM64 phone APK, runs an x86_64 emulator smoke test through the JNI generation path, and uploads the phone APK artifact. It checks out llama.cpp `v0.8.0` (`8642541b255bfb5dc39cd351a178be09013a55d6`) for LFM3 and multimodal support.

Locally, install JDK 17, Android SDK/NDK and Gradle 8.13; run `git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp` then `gradle :app:assemblePhoneDebug`. A `vMAJOR.MINOR.PATCH` tag on HEAD sets `versionName` and `versionCode`. Untagged builds are `0.1.0` (versionCode 1000).

The Stage 1 release recipe is in [`docs/RELEASE.md`](docs/RELEASE.md). Signing, Obtainium, IzzyOnDroid, and F-Droid are in [`docs/RELEASING.md`](docs/RELEASING.md).

## App navigation

The app opens on a Stillwater **Home** screen (`ComposeHostActivity`): the Hanami wordmark, **Open chat**, **History**, **Settings**, and **About**. Visual tokens and component recipes live in [`docs/DESIGN.md`](docs/DESIGN.md); UI acceptance (UT-01..UT-08) is tracked in [`docs/UI_ACCEPTANCE_MATRIX.md`](docs/UI_ACCEPTANCE_MATRIX.md).

**Chat** (`MainActivity`) is the primary viewport: structured turns, optional Reasoning rail, composer, and media controls. A session controller owns active context, cancellation, retry and Clear; JNI inference remains local. Old conversations are not injected into a new session.

**Settings** (from Home, chat **Settings**, or overflow) hosts model import / load / unload, inference limits, SearXNG, a built-in speed test (prompt-processing and decode tok/s), and **Appearance → Interface size** (Compact / Default / Large, or 80–130%). Chat keeps Offline/Search as a compact pill; model and search controls are not in the first chat viewport. Open Settings to choose context (512–8192 in steps of 256), text decode threads (1–8; default from big/mid CPU cores), and response tokens (1–2048, below context). Defaults are 4096 / topology / 512. Inference settings use DataStore and apply to the next response; interface size is stored with the private chat preferences and scales typography and spacing app-wide on top of the system font scale. Projector encoder threads remain fixed at load time. The default UI is intentionally dense (~14sp body). The arm64 native build uses `-O3` and `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` (PocketPal/llama.rn parity) while keeping `GGML_NATIVE=OFF` for reproducible cross-compiles.

**History** reuses the same turn / markdown / reasoning rendering as live chat. Emulator navigation and Stage 1 chat E2E IDs are documented in [`docs/UI_E2E_CONTRACT.md`](docs/UI_E2E_CONTRACT.md). The visible assistant role label is Hanami. The flat automator transcript still uses the marker `LFM3`.

## Usage

Import an official quantized LFM3 GGUF from **Settings → Import model** (Android document picker) to chat offline. For LFM3-Omni, import both `LFM3-Omni-2B-Q4_K_M.gguf` and its matching `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`; then attach a photo or record a voice question. Model files are copied into private app storage and are not included in the APK. Recommended phone RAM: at least 4 GB, preferably 6+ GB.

For current answers, open **Settings**, configure an HTTPS SearXNG `/search` endpoint with JSON enabled, and turn search on. Only questions sent while search is on are sent to that endpoint. Search results appear below the model's answer as clickable sources. Ordinary text chat remains local and works offline.

## Security and license

Internet access is used only for opt-in web search; search queries go to the configured SearXNG instance. Model inference and media processing run on-device. No telemetry. Source code is MIT-licensed (see LICENSE). Bundled fonts are SIL OFL 1.1 (see `licenses/fonts/`). Each model has its own Liquid AI license. CI smoke testing does not guarantee performance on every phone.
