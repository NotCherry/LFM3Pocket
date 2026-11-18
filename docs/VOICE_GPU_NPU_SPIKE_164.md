# Voice GPU/NPU acceleration spike (Vulkan first) — #164

**Issue:** [#164 [Voice next] GPU/NPU acceleration spike (Vulkan first)](https://github.com/NotCherry/LFM3Pocket/issues/164)  
**Scope:** Spike `llama.cpp` Vulkan (and note others) for Omni voice-turn latency, with clean CPU fallback. No hard NPU dependency in universal/F-Droid APK without opt-in.  
**Status:** Spike (research + prototype/config). Device TTFT/thermal benchmarks **not** run in this environment (see [Limitations](#limitations)).

## Summary

- **Current state:** Hanami loads models with `n_gpu_layers = 0` and builds `llama.cpp`/ggml CPU-only for Android (`GGML_VULKAN`/GPU backends not enabled). Multimodal projector decode is CPU-only (`mtmd_context_params.use_gpu = false`). See `app/src/main/cpp/native.cpp`, `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle`.
- **Vulkan (first target):** Feasible in principle via ggml/llama.cpp Vulkan backend, but **Android bring-up is non-trivial**: requires Vulkan-capable driver/device, Vulkan loader/headers for target, `glslc` + shader generation during build (host `vulkan-shaders-gen` toolchain considerations for cross-compile), and careful validation on Adreno/Mali drivers. This spike adds **opt-in build/config hooks** and **runtime CPU fallback** scaffolding so Vulkan can be evaluated without making Vulkan the default or breaking CPU CI.
- **OpenCL:** ggml has OpenCL support in upstream llama.cpp, but mobile OpenCL availability/performance is highly device/OEM-dependent and often less predictable than Vulkan on modern Android; treat as secondary experiment after Vulkan viability on target devices is understood.
- **Hexagon / QNN (NPU):** SoC/vendor-specific (Qualcomm-centric), typically requires vendor SDKs/runtimes and specialized integration paths; not suitable as a universal/F-Droid default dependency. Consider only as opt-in, device-specific experiments with explicit packaging/licensing review.
- **CPU fallback:** Implemented/prototyped in native load path: when Vulkan offload is explicitly enabled for a build, Hanami attempts GPU offload but **retries model load with CPU-only (`n_gpu_layers = 0`) if GPU-offload load fails**. Default builds remain CPU-only. See [Safe build/config changes](#safe-buildconfig-changes-this-spike).
- **Recommendation:** **Do not merge Vulkan as default** yet. Keep CPU as universal default; pursue Vulkan as **opt-in experimental build/flavor or runtime opt-in** after arm64 device benchmarks (TTFT delta, memory, shader/first-load overhead, thermal) on representative Adreno and Mali devices with Omni+mmproj voice-turn workload. Revisit default only with reproducible wins and robust fallback/packaging.

## Environment and limitations

| Item | This spike environment |
| --- | --- |
| Host | Linux (CI/dev container-style workspace), no Android device attached |
| `llama.cpp` | Vendored for inspection at `third_party/llama.cpp` pinned to `v0.8.0` (`8642541b...`) per README/CI; not committed (`third_party/` gitignored) |
| Android SDK/NDK / Vulkan SDK / `glslc` | Not assumed available; no on-device Vulkan validation performed here |
| Arm64 Omni+mmproj workload | Real model weights and matching projector not present; cannot run TTFT/thermal benchmarks |
| CI baseline | Existing workflows build CPU-safe artifacts; this spike must not regress CPU CI |

**Feasible in this environment:** code/config inspection, llama.cpp Vulkan build-option review, safe opt-in wiring, runtime fallback implementation, documentation/reporting, and static reasoning about failure modes.  
**Not feasible:** on-device TTFT (first token) for fixed Omni+mmproj PTT turn, thermal measurements, driver-specific behavior, APK size/startup impact with Vulkan shaders on real hardware.

## Backends evaluated (spike)

### 1) Vulkan (primary)

**Why first:** ggml provides a Vulkan backend (`GGML_VULKAN`) intended as a cross-vendor GPU path; llama.cpp exposes it as a standard CMake option. For Android, Vulkan is the most plausible "universal-ish" GPU API compared to CUDA/Metal, but practical results depend heavily on GPU driver quality, shader compiler behavior, and memory bandwidth.

**Build integration notes (`llama.cpp` v0.8.0):**
- `ggml/CMakeLists.txt` defines `GGML_VULKAN` (OFF by default) and related debug/validation/shader options.
- Vulkan backend build requires Vulkan + `glslc` and generates/compiles shaders (`ggml/src/ggml-vulkan/CMakeLists.txt`, `vulkan-shaders-gen`). Cross-compiling for Android may require providing a host toolchain for shader generation (`GGML_VULKAN_SHADERS_GEN_TOOLCHAIN`) and a valid Android Vulkan environment in the NDK/sysroot.
- Hanami currently forces CPU-oriented flags in Gradle/CMake (`GGML_BACKEND_DL=OFF`, no Vulkan args). Enabling Vulkan requires explicit opt-in and dependency/toolchain readiness.

**Expected impact on Omni voice-turn TTFT (qualitative):**
- Potential upside: offloading transformer layers to GPU can reduce prompt-eval / decode latency on some devices if memory bandwidth and kernel efficiency win over CPU NEON/DOTPROD for the chosen quant/model.
- Potential downside (mobile): shader compilation/pipeline creation overhead (especially first run), increased memory pressure (VRAM/shared allocations), driver bugs, and CPU↔GPU synchronization can negate gains or regress TTFT for short interactive turns. Multimodal (audio projector) path may remain partly/mostly CPU-bound depending on operator coverage.
- **Conclusion:** TTFT delta is **device-, driver-, and model-shape-dependent**; must be measured on target arm64 phones with the fixed Omni+mmproj PTT workload. No numeric delta is claimed here.

**Failure modes to plan for:**
- Vulkan loader/device unavailable (`vulkaninfo`/driver missing, unsupported GPU, restricted environments).
- Backend compiled in but no usable device enumerated / `llama_supports_gpu_offload()` false at runtime.
- Model load or context creation failure when `n_gpu_layers > 0` (unsupported ops, allocation failures, driver hangs/timeouts).
- Shader compile or pipeline cache issues increasing first-turn latency or causing crashes on specific drivers.
- Increased peak memory leading to LMK kills during resident voice loop.
- Regressions or instability in multimodal (`mtmd`) paths if any GPU assumptions leak into projector/audio handling.

**Mitigations:**
- Keep Vulkan **off by default**; ship CPU-only universal artifacts unless/until validated.
- **Runtime fallback to CPU-only model load** when GPU-offload load fails (implemented in this spike for opt-in builds).
- Keep projector `use_gpu` conservative unless explicitly validated.
- Gate behind explicit build flag/experiment channel; collect device reports (SoC, GPU, driver, Android version, APK SHA, model/mmproj SHAs, TTFT/thermal).

### 2) OpenCL

**Notes:** ggml/llama.cpp can be built with OpenCL on some platforms, but Android support is inconsistent (many devices lack a usable OpenCL ICD/runtime, or expose limited/slow implementations). For a universal app, OpenCL is a weaker default candidate than Vulkan.  
**Spike assessment:** **Note-only** in this environment. If pursued, treat as a targeted experiment on devices with known-good OpenCL stacks and compare against CPU and Vulkan on the same workload.

### 3) Hexagon / QNN (and other NPUs)

**Notes:** Qualcomm Hexagon DSP/NPU acceleration paths are typically accessed through vendor stacks (e.g. QNN) and may require specific SDKs, model conversion/partitioning constraints, and licensing/distribution considerations. Other SoCs (Exynos/MediaTek/Google Tensor) have different NPUs and toolchains; there is no single drop-in "NPU backend" equivalent to Vulkan for a universal APK.  
**Spike assessment:** **SoC-specific experiment only.** Not appropriate for universal/F-Droid default. Any future prototype should be opt-in, document exact hardware/software requirements, and avoid bundling proprietary blobs in the universal build.

## Safe build/config changes (this spike)

To make Vulkan evaluation possible without harming CPU CI:

1. **CMake opt-in (`app/src/main/cpp/CMakeLists.txt`):** accept explicit `GGML_VULKAN` enablement (default OFF) and define `HANAMI_ENABLE_VULKAN_OFFLOAD` for the JNI library when Vulkan is requested, while preserving existing CPU-safe defaults (`GGML_BACKEND_DL=OFF`, no OpenMP, etc.).
2. **Gradle opt-in (`app/build.gradle`):** allow passing Vulkan-related CMake arguments via project properties (e.g. `-Phanami.ggmlVulkan=ON`) without changing default `externalNativeBuild` arguments.
3. **Native runtime fallback (`native.cpp`):** in Vulkan-enabled builds, attempt GPU offload (`n_gpu_layers = -1`, subject to backend/device availability) but **fall back to a CPU-only reload** (`n_gpu_layers = 0`) if the GPU-offload load fails. CPU-only builds remain unchanged in behavior. `nativeBuildInfo()` reports Vulkan opt-in status for diagnostics.

These changes are intended to be **safe by default**: no Vulkan compiled/enabled unless explicitly requested with a capable toolchain.

## CPU fallback path ("proven" in code)

**Mechanism:** when `HANAMI_ENABLE_VULKAN_OFFLOAD` is compiled in, `loadModel`:
1. Initializes backend as usual.
2. Sets `n_gpu_layers = -1` (all layers) when `llama_supports_gpu_offload()` indicates GPU offload support may be available.
3. Attempts `llama_model_load_from_file(...)` with those params.
4. On failure (`nullptr`), retries with `n_gpu_layers = 0` (CPU-only) using the same load path.

**Proof level in this spike:** code-level fallback path implemented and reviewable; **not executed on a Vulkan-capable Android device here**. Recommended validation: build a Vulkan-enabled arm64 debug APK in an environment with Android Vulkan deps/glslc, run on devices with and without working Vulkan acceleration (or force failure), and confirm successful CPU fallback + usable inference.

## Test plan and CI safety

- **CI:** must remain **CPU-safe**. Default build flags unchanged; Vulkan opt-in only when explicitly requested.
- **Local/bench (arm64 device, manual):**
  - Fixed workload: Omni + matching mmproj, fixed PTT audio clip/turn as per voice-actions plan.
  - Record: device/SoC/GPU/driver, Android version, APK build flags + SHA, model+mmproj SHAs, ambient/skin temp notes, TTFT (time to first token/text or first audio chunk as defined), total turn latency, failures/crashes.
  - Compare: CPU baseline vs Vulkan-enabled run (same settings where possible), multiple runs, cold/warm as applicable.
  - Validate fallback: simulate/observe GPU init/load failure and confirm CPU-only recovery.

## Recommendation

- **Against merging Vulkan as the default backend now.** Rationale: unverified TTFT/thermal benefit on target Omni voice workload, high Android driver variability, added build/toolchain complexity, and risk to stability/memory on resident voice sessions.
- **For continuing as opt-in spike:** keep the config/runtime hooks, collect arm64 device evidence (Adreno + Mali), and only consider default enablement if results show consistent latency wins without unacceptable regressions in reliability, memory, or thermals.
- **OpenCL:** defer unless a specific target device class justifies it.
- **Hexagon/QNN:** pursue only as separate, device-specific, opt-in experiments; never as a universal/F-Droid hard dependency.

## References

- Issue: https://github.com/NotCherry/LFM3Pocket/issues/164
- Plan: [`docs/plans/voice-actions.md`](plans/voice-actions.md) (Voice next — #164)
- Native load/build: `app/src/main/cpp/native.cpp`, `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle`
- `llama.cpp` Vulkan docs (upstream, v0.8.0 tree): `third_party/llama.cpp/docs/build.md` (`Vulkan` section), `third_party/llama.cpp/ggml/src/ggml-vulkan/CMakeLists.txt`
