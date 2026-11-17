# Third-party notices (Hanami APK)

Hanami (`dev.notcherry.hanami`) ships third-party libraries in its APK alongside the MIT-licensed application source ([`LICENSE`](../LICENSE)). This file inventories what is **compiled or packaged into the APK** as of the pins in this repository. Optional assets downloaded after install (GGUF weights, tiny-voca pack) are listed separately.

Bundled UI fonts are documented in [`licenses/fonts/NOTICE.md`](fonts/NOTICE.md).

**Follow-up:** These notices live in the source tree and release documentation. Embedding them inside the APK (Settings or assets) is not done in this change.

## Native code (JNI / `.so`)

| Component | Version / commit | License | How it ships | Source |
| --- | --- | --- | --- | --- |
| **llama.cpp** (incl. ggml, mtmd) | tag `v0.8.0`, commit `8642541b255bfb5dc39cd351a178be09013a55d6` | MIT | Statically linked into `liblfm3pocket.so` at build time (`third_party/llama.cpp`, CI/F-Droid fetch) | https://github.com/ggml-org/llama.cpp |
| **ONNX Runtime** | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (upstream tag `v1.20.0`, commit `1a313abba7f72af26c1e9c7e0b1688eae9952644`) | MIT | `libonnxruntime*.so` and Java API from the AAR (`app/build.gradle`). Microsoft’s aggregated third-party notices (protobuf, abseil-cpp, …): [`licenses/onnxruntime-ThirdPartyNotices.txt`](onnxruntime-ThirdPartyNotices.txt) | https://github.com/microsoft/onnxruntime |
| **voca / dizione** (phonemizer) | commit `03f91ae073fce4fce8852323efe3345dfe4048f5` (`vendor/VOCA_COMMIT`, `scripts/fetch-voca.sh`) | Apache-2.0 | `libhanami_dizione.so` when built (`scripts/build-dizione-jni.sh`). Runtime pack files may be downloaded separately; static rule tables are compiled in via `include_str!` | https://github.com/sultson/voca |
| **CMUdict-derived data** | Carnegie Mellon University CMUdict (via dizione) | CMU permissive (see file) | Embedded in dizione rule data / lexicon pipeline in `libhanami_dizione.so` and optional tiny-voca phonemizer files | https://github.com/cmusphinx/cmudict |
| **Rust crates in `libhanami_dizione.so`** | `app/src/main/rust/hanami_dizione/Cargo.lock` | Mixed (see generated doc) | Full crate list + license texts: [`licenses/hanami_dizione-rust-THIRD_PARTY.md`](hanami_dizione-rust-THIRD_PARTY.md). Regenerate: `bash scripts/generate-dizione-licenses.sh` | https://github.com/EmbarkStudios/cargo-about |
| **LLVM libc++** | Android NDK `27.2.12479018` (`ANDROID_STL=c++_shared`) | Apache-2.0 WITH LLVM-exception | `libc++_shared.so` packaged with the APK. Full text: [`licenses/libcxx-LICENSE.TXT`](libcxx-LICENSE.TXT) | https://github.com/llvm/llvm-project (libc++) |

Attribution for voca: [`licenses/voca-NOTICE.txt`](voca-NOTICE.txt).

## JVM / Kotlin dependencies (Gradle)

Declared in [`app/build.gradle`](../app/build.gradle). **Bill of Materials (BOM) coordinates are version catalogs only — they are not packaged as artifacts.** Resolved library versions below are what ship in the APK.

| Artifact | Version | Notes |
| --- | --- | --- |
| `androidx.camera:camera-camera2` | 1.5.3 | Apache-2.0 |
| `androidx.camera:camera-lifecycle` | 1.5.3 | Apache-2.0 |
| `androidx.camera:camera-view` | 1.5.3 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2025.04.01 | **BOM only** (not shipped) |
| `androidx.compose.material3:material3` | 1.3.2 | via Compose BOM; Apache-2.0 |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 |
| `androidx.navigation:navigation-compose` | 2.8.9 | Apache-2.0 |
| `androidx.room:room-runtime` | 2.8.5 | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.1.7 | Apache-2.0 |
| `androidx.exifinterface:exifinterface` | 1.3.7 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.8.1 | **BOM only** (not shipped) |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.20.0 | MIT (+ third-party notices above) |

Other transitive AndroidX / Kotlin runtime libraries inherit upstream Apache-2.0 (AndroidX) or project licenses. Standard Apache-2.0 text: [`licenses/Apache-2.0.txt`](Apache-2.0.txt).

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
| ONNX Runtime bundled deps | [`licenses/onnxruntime-ThirdPartyNotices.txt`](onnxruntime-ThirdPartyNotices.txt) |
| Apache-2.0 (standard) | [`licenses/Apache-2.0.txt`](Apache-2.0.txt) |
| voca attribution (NOTICE) | [`licenses/voca-NOTICE.txt`](voca-NOTICE.txt) |
| CMUdict | [`licenses/CMUdict.txt`](CMUdict.txt) |
| LLVM libc++ (with LLVM exception) | [`licenses/libcxx-LICENSE.TXT`](libcxx-LICENSE.TXT) |
| `libhanami_dizione.so` Rust crates | [`licenses/hanami_dizione-rust-THIRD_PARTY.md`](hanami_dizione-rust-THIRD_PARTY.md) |
| Bundled fonts (SIL OFL 1.1) | [`licenses/fonts/OFL-1.1.txt`](fonts/OFL-1.1.txt) |

Hanami application source remains MIT. Third-party licenses apply only to their respective components.