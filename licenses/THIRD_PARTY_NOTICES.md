# Third-party notices (Hanami APK)

Hanami (`dev.notcherry.hanami`) ships third-party libraries in its APK alongside the MIT-licensed application source ([`LICENSE`](../LICENSE)). This file inventories what is **compiled or packaged into the APK** as of the pins in this repository. Optional assets downloaded after install (GGUF weights, tiny-voca pack) are listed separately.

Bundled UI fonts are documented in [`licenses/fonts/NOTICE.md`](fonts/NOTICE.md).

## Native code (JNI / `.so`)

| Component | Version / commit | License | How it ships | Source |
| --- | --- | --- | --- | --- |
| **llama.cpp** (incl. ggml, mtmd) | tag `v0.8.0`, commit `8642541b255bfb5dc39cd351a178be09013a55d6` | MIT | Statically linked into `liblfm3pocket.so` at build time (`third_party/llama.cpp`, CI/F-Droid fetch) | https://github.com/ggml-org/llama.cpp |
| **ONNX Runtime** | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (upstream tag `v1.20.0`, commit `1a313abba7f72af26c1e9c7e0b1688eae9952644`) | MIT | `libonnxruntime*.so` and Java API from the AAR (`app/build.gradle`) | https://github.com/microsoft/onnxruntime |
| **voca / dizione** (phonemizer) | commit `03f91ae073fce4fce8852323efe3345dfe4048f5` (`vendor/VOCA_COMMIT`, `scripts/fetch-voca.sh`) | Apache-2.0 | `libhanami_dizione.so` (Rust `dizione` + embedded phonemizer data); built when voca + NDK + Rust are available | https://github.com/sultson/voca |
| **jni** (Rust crate) | `0.21.1` (`app/src/main/rust/hanami_dizione/Cargo.lock`) | Apache-2.0 OR MIT | Linked into `libhanami_dizione.so` | https://github.com/jni-rs/jni-rs |
| **LLVM libc++** | From Android NDK `27.2.12479018` (`ANDROID_STL=c++_shared`) | Apache-2.0 with LLVM exceptions | `libc++_shared.so` | https://github.com/android/ndk |

Dizione phonemizer data includes CMUdict-derived material; see [`licenses/voca-NOTICE.txt`](voca-NOTICE.txt).

## JVM / Kotlin dependencies (Gradle)

Declared in [`app/build.gradle`](../app/build.gradle). Jetpack Compose libraries use versions from BOM `androidx.compose:compose-bom:2025.04.01` (e.g. `material3` → `1.3.2`). All listed artifacts are **Apache-2.0** unless noted.

| Artifact | Version |
| --- | --- |
| `androidx.camera:camera-camera2` | 1.5.3 |
| `androidx.camera:camera-lifecycle` | 1.5.3 |
| `androidx.camera:camera-view` | 1.5.3 |
| `androidx.compose:compose-bom` | 2025.04.01 |
| `androidx.compose.material3:material3` | 1.3.2 (via BOM) |
| `androidx.activity:activity-compose` | 1.10.1 |
| `androidx.navigation:navigation-compose` | 2.8.9 |
| `androidx.room:room-runtime` | 2.8.5 |
| `androidx.datastore:datastore-preferences` | 1.1.7 |
| `androidx.exifinterface:exifinterface` | 1.3.7 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.8.1 |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.20.0 |

Transitive AndroidX / Kotlin standard library dependencies inherit the same upstream licenses (Apache-2.0 for AndroidX). Full Apache-2.0 text: [`licenses/voca-Apache-2.0.txt`](voca-Apache-2.0.txt) (standard license text).

## Optional downloads (not bundled in the APK)

| Component | Pin | License | Notes |
| --- | --- | --- | --- |
| **tiny-voca** (English TTS weights + phonemizer files) | Hugging Face `n4ze3m/tiny-voca` @ `d94d2a0338fd1e0eab7dd896ec0ad23d8cdf7c14` (`TinyVocaPackManifest`) | Apache-2.0 | User downloads into private app storage; ONNX Runtime in the APK runs the model |
| **Liquid AI LFM3 / LFM3-Omni GGUF** | User-selected import | Per-model (Liquid AI) | Never included in the APK |

## Full license texts (required notices)

| License | File |
| --- | --- |
| llama.cpp (MIT) | [`licenses/llama.cpp-MIT.txt`](llama.cpp-MIT.txt) |
| ONNX Runtime (MIT) | [`licenses/onnxruntime-MIT.txt`](onnxruntime-MIT.txt) |
| voca / dizione / tiny-voca family (Apache-2.0) | [`licenses/voca-Apache-2.0.txt`](voca-Apache-2.0.txt) |
| voca attribution (NOTICE) | [`licenses/voca-NOTICE.txt`](voca-NOTICE.txt) |
| Bundled fonts (SIL OFL 1.1) | [`licenses/fonts/OFL-1.1.txt`](fonts/OFL-1.1.txt) |

Hanami application source remains MIT. Third-party licenses apply only to their respective components.