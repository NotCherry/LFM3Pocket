# ARM64 decode performance — PocketPal / ChatterUI comparison

Owner report: the same LFM3 Q4_K_M GGUF ran at ~10+ tok/s decode in PocketPal AI
([llama.rn](https://github.com/mybigday/llama.rn)) and ChatterUI
([cui-llama.rn](https://github.com/Vali-98/cui-llama.rn)), but only ~4 tok/s in Hanami.

## What the peers do

| Setting | PocketPal (llama.rn) | ChatterUI (cui-llama.rn) | Hanami before | Hanami after |
| --- | --- | --- | --- | --- |
| CPU ISA | Runtime ladder; prefers `armv8.2-a+dotprod[+i8mm]` | Same ladder via cui-llama.rn | Baseline NEON (`GGML_NATIVE=OFF`, empty `GGML_CPU_ARM_ARCH`) | Single `arm64-v8a` `.so` with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` |
| `-O3` | Always (`-O3 -DNDEBUG` on kernels) | Same | Debug APKs used NDK Debug/`-O2` | Debug+Release force `-O3 -DNDEBUG` |
| OpenMP | Off for Android | Off | Off | Off (unchanged; llama.cpp android.md) |
| KleidiAI | Not required for CPU ladder | Not required | Off | Off (FetchContent breaks offline/F-Droid) |
| Threads default | 80% of cores if &gt;4 | `availableProcessors()` | Fixed 4 | Big+mid frequency clusters (PocketPal 80% fallback) |
| `n_batch` | 512 | 512 | 512 | ≤1024 when context allows |
| Flash attn | Off on Android CPU | Default / unset | llama.cpp AUTO | Explicit AUTO |
| mmap / mlock | mmap often false on Android; mlock false | mmap+mlock true | mmap AUTO, no mlock | Unchanged (no mlock; LMK policy) |
| Context lifetime | Kept live (KV reuse) | Kept live + optional session file | Freed every `generateUtf8` | Persistent text session + prefix `seq_rm` |

## Expected impact

| Change | Expected impact |
| --- | --- |
| `armv8.2-a+dotprod` | Primary decode win for Q4_K_M (DOTPROD GEMM). Reasoned: ~1.5–2.5× tg vs baseline NEON on modern phones — closes most of 4→10+ tok/s. |
| `-O3` on Debug | CI/Obtainium Debug APKs stop shipping under-optimized kernels. ~10–30% if previously `-O0`/`-O2`. |
| KV / prefix reuse | Multi-turn chat stops re-prefilling the shared history each send. Large pp saving after turn 1; tg unchanged. |
| Topology thread default | Avoid under-subscribing big/mid cores (fixed 4). Device-dependent; often modest tg gain. |
| `n_batch` ≤1024 | Faster prompt processing; little effect on single-token decode. |
| Infer thread priority | Avoids occasional background nice; small/situational. |

## Before / after tok/s

- **Before (owner, physical arm64):** ~4 tok/s decode for LFM3 Q4_K_M.
- **Peer apps (owner):** 10+ tok/s decode, same weights.
- **After (reasoned):** ~8–12 tok/s decode on the same class of phone once DOTPROD + `-O3` land; multi-turn pp much lower wall time via KV reuse. Emulator x86_64 is not representative of arm64 DOTPROD — use Settings → **Run speed test** on device and copy the pp/tg report.

## Reproducibility

Still one `arm64-v8a` ABI, `GGML_NATIVE=OFF`, pinned NDK/CMake/llama.cpp commit. i8mm is intentionally omitted from the single binary so Snapdragon 720G-class SoCs (dotprod yes, i8mm no) keep working without a PocketPal-style multi-`.so` ladder.
