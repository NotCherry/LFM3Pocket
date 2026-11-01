# LFM3 Pocket

Minimal, fully offline Android ARM64 chat application for [Liquid AI LFM3-1.7B GGUF](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF) using native [llama.cpp](https://github.com/ggml-org/llama.cpp).

## Build

GitHub Actions runs `.github/workflows/build-apk.yml`, checks out llama.cpp `v0.8.0` (minimum version with LFM3 support), installs Android SDK/NDK, builds `:app:assembleDebug`, and uploads a debug APK artifact.

Locally, install JDK 17, Android SDK/NDK and Gradle 8.13; run `git clone --branch v0.8.0 --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp` then `gradle :app:assembleDebug`.

## Usage

Download an official quantized `LFM3-1.7B-Q4_K_M.gguf` model, start the app, import it using the Android document picker, then chat offline. Models are copied into private app storage. A model is not included in the APK. Recommended phone RAM: at least 4 GB, preferably 6+ GB.

## Security and license

No `INTERNET` permission and no telemetry. llama.cpp is downloaded at build time only. Source code of this app is MIT-licensed (see LICENSE); model has its own Liquid AI license. Device inference is not guaranteed by CI compilation alone.
