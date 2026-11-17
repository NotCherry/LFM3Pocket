#pragma once

#include "common.h"
#include "llama.h"
#include "sampling.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace hanami {
struct SpecDecodeStats {
    int n_predict = 0;
    int n_drafted = 0;
    int n_accept = 0;
    /** Tokens submitted to decode batches (for self-check). */
    int n_eval_tokens = 0;
    /** Must be 0 — last prompt token must not be in KV before the spec loop starts. */
    int n_prompt_tail_duplicate = 0;
};

struct TokenDelivery {
    JNIEnv * env = nullptr;
    jobject sink = nullptr;
    jmethodID on_token = nullptr;
    std::string * pending = nullptr;
    std::string * error = nullptr;
};

void configure_phone_ngram_spec(common_params_speculative & spec);

common_sampler * make_chat_sampler(const llama_model * model);

/**
 * Drop the last prompt token from KV so the ngram-mod driver can evaluate it exactly once
 * (Hanami prefills the full prompt before calling this).
 */
bool drop_last_prompt_token_from_kv(llama_context * ctx, llama_seq_id seq_id, size_t prompt_token_count,
                                    std::string & error);

size_t kv_token_count(llama_context * ctx, llama_seq_id seq_id);

// Target-model ngram-mod speculative decode (no draft GGUF). KV must contain prompt minus last token.
bool generate_ngram_mod(
        llama_context * ctx,
        const llama_vocab * vocab,
        bool mrope,
        const std::vector<llama_token> & prompt_tokens,
        int max_new_tokens,
        int context_tokens,
        std::atomic<bool> * cancelled,
        TokenDelivery * delivery,
        std::vector<llama_token> & generated,
        std::vector<llama_token> * session_tokens,
        SpecDecodeStats * stats,
        std::string & error);

bool generate_sequential(
        llama_context * ctx,
        llama_batch_ext * batch,
        const llama_vocab * vocab,
        bool mrope,
        int start_position,
        int max_new_tokens,
        int context_tokens,
        std::atomic<bool> * cancelled,
        std::vector<llama_token> & generated,
        std::string & error);

} // namespace hanami