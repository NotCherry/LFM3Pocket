# ADR 0011 — ngram-mod prompt lookup (speculative decode without a draft model)

- Status: Accepted
- Date: 2026-11-17

## Context

Hanami targets mid/low-end arm64 phones with a single LFM2/LFM3 GGUF (hundreds of MB–few GB RAM).
Owner goal: faster decode without another large download.

llama.cpp v0.8.0 (`8642541b255bfb5dc39cd351a178be09013a55d6`) supports several speculative paths in
`common/speculative.cpp`:

| Approach | Extra download | RAM | On-device fit |
| --- | --- | --- | --- |
| Draft model (simple / MTP / Eagle / DFlash) | Second GGUF, vocab must match | ~doubles weights + second context | Poor on 4 GB phones |
| `ngram-cache` | Optional static corpus file | Cache files | Skipped (no bundled corpus) |
| `ngram-simple` | None | Small | Moderate; mostly prompt-local repeats |
| **`ngram-mod`** | **None** | ~4 MB open-address table | **Best fit when enabled** |

`ngram-mod` drafts up to *M* tokens from an in-memory n-gram map built from the live prompt and accepted
generation; the **target** model verifies drafts in one batched `llama_process` (same acceptance rules as
`common_sampler_sample_and_accept_n`). Output distribution matches sequential sampling when verification
succeeds (llama.cpp guarantees for this path).

LFM hybrid memory may not support partial `llama_memory_seq_rm`; Hanami already handles that for chat KV.
The speculative driver uses llama.cpp **sequence checkpoints** when partial removal is unavailable (same
as `examples/speculative-simple`).

## Decision

1. Link `llama-common` (static) from the JNI build and enable **`COMMON_SPECULATIVE_TYPE_NGRAM_MOD`**
   behind **Settings → Prompt lookup decode (ngram-mod)** (`settings_prompt_lookup`), persisted in the
   inference DataStore key `prompt_lookup`.
2. **Default: OFF** until measured on a physical arm64 device (bounded ~4 MB table, no extra GGUF).
   Users opt in via Settings; multimodal turns keep the sequential decoder regardless.
3. Phone-tuned params (smaller than llama-cli defaults tuned for server GPUs):
   - `n_match = 16`, `n_min = 2`, `n_max = 12`
4. Settings **Run speed test** (`settings_bench_run`) reports **sequential** and **ngram-mod** tg tok/s
   plus draft acceptance % on the loaded model (emulator x86_64 is not representative of arm64 DOTPROD).

Draft-model speculative decoding remains **out of scope** until users can opt into a second small GGUF
without blowing the 4 GB budget.

## Consequences

- APK/native link step grows (llama-common objects); no new network fetch.
- Best speedups when assistant output reuses prompt/history n-grams (templated answers, lists, code
  scaffolding). Creative paraphrase may see little gain — acceptance rate drops, overhead is small.
- Hybrid LFM contexts pay checkpoint cost on partial draft rejection; worst case approaches sequential
  tok/s for that turn.
- Measurement on physical arm64 (e.g. RMX2170 + LFM3 Q4_K_M) is still required for honest before/after
  numbers; CI smoke only proves the JNI path runs.

## References

- [`PERF_ARM64.md`](../PERF_ARM64.md) — benchmark fields
- [`UI_E2E_CONTRACT.md`](../UI_E2E_CONTRACT.md) — `settings_prompt_lookup`, `settings_bench_*`
- llama.cpp: `common/speculative.cpp`, `examples/speculative-simple/speculative-simple.cpp`