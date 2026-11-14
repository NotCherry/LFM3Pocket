# LFM3 Pocket

On-device Android chat for Liquid AI LFM3 using native [llama.cpp](https://github.com/ggml-org/llama.cpp). Text inference stays on the phone. Optional web search uses a SearXNG endpoint configured by the user.

## Download

Installable ARM64 APKs are published on the [Releases](https://github.com/notcherry/lfm3pocket/releases) page:

- **Latest CI build** (pre-release) — refreshed automatically after each green `main` CI run
- Numbered **`v*`** releases — created when a version tag is pushed (for example `v0.1.0`)

Model weights are never bundled in the APK; import LFM3 / LFM3-Omni GGUFs in-app after install.

## Build

GitHub Actions builds the ARM64 phone APK, runs an x86_64 emulator smoke test through the JNI generation path, and uploads the phone APK artifact. It checks out llama.cpp `v0.8.0` for LFM3 and multimodal support.

Locally, install JDK 17, Android SDK/NDK and Gradle 8.13; run `git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp` then `gradle :app:assemblePhoneDebug`.

The Stage 1 release recipe — clean-checkout steps, CI artifacts, GitHub Releases, model import, device limitations, and license notices — is in [`docs/RELEASE.md`](docs/RELEASE.md).

## App navigation

The app opens on an Atlas **Home** screen (`ComposeHostActivity`): brand-first charcoal shell with **Open chat**, **History**, **Settings**, and **About**. Visual tokens and component recipes live in [`docs/DESIGN.md`](docs/DESIGN.md); UI acceptance (UT-01..UT-08) is tracked in [`docs/UI_ACCEPTANCE_MATRIX.md`](docs/UI_ACCEPTANCE_MATRIX.md).

**Chat** (`MainActivity`) is the primary viewport: structured turns, optional Reasoning rail, composer, and media controls. A session controller owns active context, cancellation, retry and Clear; JNI inference remains local. Old conversations are not injected into a new session.

**Settings** (from Home, chat **Settings**, or overflow) hosts model import / load / unload, inference limits, and SearXNG. Chat keeps Offline/Search as a compact pill; model and search controls are not in the first chat viewport. Open Settings to choose context (512–8192 in steps of 256), text decode threads (1–8, capped to available CPUs), and response tokens (1–2048, below context). Defaults are 4096 / 4 / 512. Settings are stored privately with DataStore and apply to the next response. Projector encoder threads remain fixed at load time.

**History** reuses the same turn / markdown / reasoning rendering as live chat. Emulator navigation and Stage 1 chat E2E IDs are documented in [`docs/UI_E2E_CONTRACT.md`](docs/UI_E2E_CONTRACT.md).

## Usage

Import an official quantized LFM3 GGUF from **Settings → Import model** (Android document picker) to chat offline. For LFM3-Omni, import both `LFM3-Omni-2B-Q4_K_M.gguf` and its matching `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`; then attach a photo or record a voice question. Model files are copied into private app storage and are not included in the APK. Recommended phone RAM: at least 4 GB, preferably 6+ GB.

For current answers, open **Settings**, configure an HTTPS SearXNG `/search` endpoint with JSON enabled, and turn search on. Only questions sent while search is on are sent to that endpoint. Search results appear below the model's answer as clickable sources. Ordinary text chat remains local and works offline.

## Security and license

Internet access is used only for opt-in web search; search queries go to the configured SearXNG instance. Model inference and media processing run on-device. No telemetry. Source code is MIT-licensed (see LICENSE); each model has its own Liquid AI license. CI smoke testing does not guarantee performance on every phone.

