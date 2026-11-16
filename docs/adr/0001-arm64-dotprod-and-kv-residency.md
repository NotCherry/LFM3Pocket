# ADR 0001 — arm64 DOTPROD kernels and text KV residency

- Status: Accepted
- Date: 2026-11-16
- PR: #172

## Context

Hanami ships a single `arm64-v8a` `.so` for F-Droid / reproducible builds
(`GGML_NATIVE=OFF`, no `GGML_BACKEND_DL`). PocketPal / ChatterUI (llama.rn) ship a
runtime ladder of CPU variants and pick `armv8.2-a+dotprod` (and optionally
`+i8mm`) at load time. Without an explicit `GGML_CPU_ARM_ARCH`, ggml left
`ARCH_FLAGS` empty and Hanami decoded ~2× slower than those apps on the same
Q4_K_M weights.

Separately, keeping a live `llama_context` across chat turns reuses the KV /
hybrid memory for shared prompt prefixes. That conflicts with the Phase-1 note in
`docs/plans/background-mode.md` that idle residency is weights only.

## Decision

### 1. Compile flags (phone `arm64-v8a`)

| Flag | Value | Why |
| --- | --- | --- |
| `GGML_NATIVE` | `OFF` | Required for cross-compile / F-Droid |
| `GGML_CPU_ARM_ARCH` | `armv8.2-a+dotprod` | Same preferred CPU step as llama.rn `v8_2_dotprod` |
| `+i8mm` | **Omitted** | Snapdragon 720G (RMX2170) has DOTPROD, not i8mm |
| `GGML_OPENMP` | `OFF` | llama.cpp Android NDK limitation |
| `GGML_CPU_KLEIDIAI` | `OFF` | FetchContent breaks offline / reproducible builds |
| Debug `-O3` | Via `add_compile_options` (not `CACHE FORCE`) | CI ships Debug APKs; keep NDK flags and asserts (`-DNDEBUG` not forced in Debug) |

### 2. Devices without DOTPROD

The phone flavor **requires** DOTPROD (Cortex-A76 / Snapdragon 845-class and newer).
Older cores (A53/A57/A72/A73) would hit `SIGILL` inside ggml kernels.

They still **use the app** without crashing:

1. Java checks `/proc/cpuinfo` for `dotprod` / `asimddp` before calling into
   decode-heavy JNI (`NativeInferenceEngine.loadModel`).
2. If missing, load fails with a clear English message instead of starting
   inference.
3. x86_64 `smoke` / emulator builds do not set `GGML_CPU_ARM_ARCH`, so CI is
   unaffected.
4. A future PocketPal-style multi-`.so` ladder can widen support without changing
   this ADR’s baseline.

### 3. Persistent text KV / hybrid memory

LFM2/LFM3 are **hybrid** architectures in llama.cpp (`llama_model_is_hybrid`).
Partial `llama_memory_seq_rm` can fail; on failure Hanami clears memory and
re-prefills from scratch. Cancel / trim / unload always drop the session.

Idle residency:

- Weights stay mapped while “keep loaded” is on (unchanged).
- The text session (`llama_context` + KV/hybrid state) is released on
  `onTrimMemory` critical/complete **before** policy unload, on explicit unload,
  after media/speech turns, and before the Settings benchmark allocates a fresh
  context.
- Background-mode docs are updated to match: idle may hold weights; KV is not a
  guaranteed idle resident.

## Consequences

- Phone APKs are faster on supported SoCs; unsupported SoCs get a readable error.
- Multi-turn chat can skip shared-prefix prefill when hybrid memory allows.
- Peak RAM while chatting is higher than “weights only” until trim/unload; trim
  must release the session (implemented alongside this ADR).
