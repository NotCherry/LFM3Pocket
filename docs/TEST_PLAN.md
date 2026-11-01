# Verification plan

## In GitHub Actions

- Checkout llama.cpp `v0.8.0` (first release documented to support LFM3).
- Cross-compile native C++ for `arm64-v8a` with Android NDK 27.
- Compile Java and package signed debug APK with no `INTERNET` permission.
- Upload `LFM3Pocket-arm64-debug` artifact.

## Device test (requires Android ARM64 device and official GGUF)

1. Install APK, open it, confirm no Internet permission is requested.
2. Import official `LFM3-1.7B-Q4_K_M.gguf` using document picker.
3. Confirm private storage import and native model load.
4. Send a Polish prompt; confirm incremental output, unicode, and chat history.
5. Tap Stop during generation, then send another prompt.
6. Clear conversation; restart app; confirm saved model reload.
7. Test airplane mode and measure memory/heat/battery.

CI compilation **does not** prove GGUF loading or phone-side inference. The app never bundles model weights.
