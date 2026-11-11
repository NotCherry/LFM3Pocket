# LFM3 Pocket

On-device Android chat for Liquid AI LFM3 using native [llama.cpp](https://github.com/ggml-org/llama.cpp). Text inference stays on the phone. Optional web search uses a SearXNG endpoint configured by the user.

## Build

GitHub Actions builds the ARM64 phone APK, runs an x86_64 emulator smoke test through the JNI generation path, and uploads the phone APK artifact. It checks out llama.cpp `v0.8.0` for LFM3 and multimodal support.

Locally, install JDK 17, Android SDK/NDK and Gradle 8.13; run `git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp` then `gradle :app:assemblePhoneDebug`.

## App navigation

The app opens in a minimal Kotlin/Compose navigation host. Tap **Open chat** for Compose streaming chat. A session controller owns active context, cancellation, retry and Clear; JNI inference remains local. Old conversations are not injected into a new session. This staged migration is covered by emulator navigation tests.

The chat model manager shows selected model/projector names, import progress and compatibility failures. Use **Unload model** to release native memory while retaining selected files, **Load model** or **Reload model** to load them again, and **Retry load** after a recoverable failure. Imports use the Android document picker and **Stop** cancels copying.

## Usage

Import an official quantized LFM3 GGUF from the Android document picker to chat offline. For LFM3-Omni, import both `LFM3-Omni-2B-Q4_K_M.gguf` and its matching `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`; then attach a photo or record a voice question. Model files are copied into private app storage and are not included in the APK. Recommended phone RAM: at least 4 GB, preferably 6+ GB.

For current answers, open **Web search**, configure an HTTPS SearXNG `/search` endpoint with JSON enabled, and turn search on. Only questions sent while search is on are sent to that endpoint. Search results appear below the model's answer as clickable sources. Ordinary text chat remains local and works offline.

## Security and license

Internet access is used only for opt-in web search; search queries go to the configured SearXNG instance. Model inference and media processing run on-device. No telemetry. Source code is MIT-licensed (see LICENSE); each model has its own Liquid AI license. CI smoke testing does not guarantee performance on every phone.

