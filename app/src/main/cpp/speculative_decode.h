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

// Target-model ngram-mod speculative decode (no draft GGUF). Prompt must already be in KV.
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
        SpecDecodeStats * stats,
        std::string & error);

// Same sampler path as chat, sequential single-token decode (benchmark baseline).
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