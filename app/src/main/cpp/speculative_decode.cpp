#include "speculative_decode.h"
#include "repetition_guard.h"
#include "ngram-mod.h"

#include <jni.h>
#include <algorithm>
#include <memory>

namespace {
// Phone-tuned ngram-mod parameters (see docs/adr/0011-ngram-mod-prompt-lookup.md).
constexpr uint16_t kNgramMatch = 16;
constexpr int kNgramMaxDraft = hanami::kNgramMaxDraft;
constexpr int kNgramMinDraft = 2;
constexpr size_t kNgramTableEntries = 1024 * 1024; // 4 MB open-address table (int32_t entries)

// Liquid AI LFM3 llama.cpp chat sampling defaults (docs.liquid.ai/deployment/on-device/llama-cpp/chat).
constexpr int32_t kChatTopK = 50;
constexpr int32_t kChatPenaltyLastN = 64;
constexpr float kChatRepeatPenalty = 1.1f;
constexpr float kChatTemp = 0.1f;

static_assert(sizeof(llama_token) == sizeof(common_ngram_mod::entry_t),
              "llama_token must match common_ngram_mod::entry_t");
static_assert(sizeof(llama_token) == sizeof(int32_t),
              "llama_token must match int32_t for repetition_guard");

class NgramModDrafter {
public:
    NgramModDrafter() : mod_(kNgramMatch, kNgramTableEntries) {}

    void begin(const std::vector<llama_token> & prompt) {
        i_last_ = 0;
        n_draft_last_ = 0;
        n_low_ = 0;
        const size_t n = mod_.get_n();
        if (prompt.size() < n) return;
        for (size_t i = 0; i < prompt.size() - n; ++i) {
            mod_.add(prompt.data() + i);
        }
        i_last_ = prompt.size() - n;
        const double occupancy = static_cast<double>(mod_.get_used()) / static_cast<double>(mod_.size());
        if (occupancy > 0.25) {
            mod_.reset();
        }
    }

    void draft(llama_token id_last, const std::vector<llama_token> & prompt,
               int max_draft, std::vector<llama_token> & result) {
        result.clear();
        n_draft_last_ = 0;
        const size_t n = mod_.get_n();
        const size_t cur_len = prompt.size();
        const int limit = std::min(max_draft, kNgramMaxDraft);
        if (cur_len < n || limit < kNgramMinDraft) return;

        // Add new n-grams in chunks matching upstream common_speculative_impl_ngram_mod.
        if (i_last_ + 32 < cur_len) {
            for (size_t i = i_last_; i < cur_len - n; ++i) {
                mod_.add(prompt.data() + i);
            }
            i_last_ = cur_len - n;
        }

        result.resize(n + static_cast<size_t>(limit));
        for (size_t i = 0; i < n - 1; ++i) {
            result[i] = prompt[cur_len - n + 1 + i];
        }
        result[n - 1] = id_last;

        for (int i = 0; i < limit; ++i) {
            const llama_token token = mod_.get(result.data() + i);
            if (token == common_ngram_mod::EMPTY) {
                if (i < kNgramMinDraft) {
                    result.clear();
                    return;
                }
                result.resize(n + static_cast<size_t>(i));
                break;
            }
            result[n + static_cast<size_t>(i)] = token;
        }

        for (size_t i = 0; n + i < result.size(); ++i) {
            result[i] = result[n + i];
        }
        result.resize(result.size() - n);
        n_draft_last_ = result.size();
    }

    void accept(size_t n_accepted) {
        if (n_draft_last_ == 0) return;
        const double ratio = static_cast<double>(n_accepted) / static_cast<double>(n_draft_last_);
        if (ratio < 0.25) {
            n_low_++;
            if (n_low_ >= 5) {
                mod_.reset();
                n_low_ = 0;
                i_last_ = 0;
            }
        } else {
            n_low_ = 0;
        }
    }

private:
    common_ngram_mod mod_;
    size_t i_last_ = 0;
    size_t n_draft_last_ = 0;
    int n_low_ = 0;
};

struct SamplerDeleter {
    void operator()(llama_sampler * s) const {
        if (s) llama_sampler_free(s);
    }
};

struct BatchDeleter {
    void operator()(llama_batch_ext * b) const {
        if (b) llama_batch_ext_free(b);
    }
};

using SamplerPtr = std::unique_ptr<llama_sampler, SamplerDeleter>;
using BatchPtr = std::unique_ptr<llama_batch_ext, BatchDeleter>;

std::vector<llama_token> sample_and_accept_n(
        llama_sampler * smpl,
        llama_context * ctx,
        const llama_vocab * vocab,
        const std::vector<llama_token> & draft) {
    std::vector<llama_token> result;
    result.reserve(draft.size() + 1);
    size_t i = 0;
    for (; i < draft.size(); ++i) {
        const llama_token id = llama_sampler_sample(smpl, ctx, static_cast<int32_t>(i));
        llama_sampler_accept(smpl, id);
        result.push_back(id);
        if (draft[i] != id || (llama_vocab_is_eog(vocab, id) && i + 1 < draft.size())) {
            break;
        }
    }
    if (i == draft.size()) {
        const llama_token id = llama_sampler_sample(smpl, ctx, static_cast<int32_t>(i));
        llama_sampler_accept(smpl, id);
        result.push_back(id);
    }
    return result;
}

bool deliver_piece(JNIEnv * env, jobject sink, jmethodID on_token, std::string & pending,
                   const std::u16string & piece, std::string & error) {
    if (!env || !sink || !on_token || piece.empty()) return true;
    const jchar * data = reinterpret_cast<const jchar *>(piece.data());
    const jsize len = static_cast<jsize>(piece.size());
    jstring out = env->NewString(data, len);
    if (!out) return false;
    env->CallVoidMethod(sink, on_token, out);
    env->DeleteLocalRef(out);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        error = "Java callback failed";
        return false;
    }
    (void) pending;
    return true;
}

// Same UTF-8 prefix decode as native.cpp (duplicate to avoid circular deps).
std::u16string decode_utf8_prefix(std::string & pending) {
    std::u16string decoded;
    size_t i = 0;
    while (i < pending.size()) {
        const uint8_t lead = static_cast<uint8_t>(pending[i]);
        uint32_t cp = 0;
        size_t width = 0;
        if (lead < 0x80) { cp = lead; width = 1; }
        else if (lead >= 0xC2 && lead <= 0xDF) { cp = lead & 0x1F; width = 2; }
        else if (lead >= 0xE0 && lead <= 0xEF) { cp = lead & 0x0F; width = 3; }
        else if (lead >= 0xF0 && lead <= 0xF4) { cp = lead & 0x07; width = 4; }
        else { decoded.push_back(u'\uFFFD'); ++i; continue; }

        if (i + width > pending.size()) break;
        bool valid = true;
        for (size_t j = 1; j < width; ++j) {
            uint8_t x = static_cast<uint8_t>(pending[i + j]);
            if ((x & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (x & 0x3F);
        }
        if (!valid) { decoded.push_back(u'\uFFFD'); ++i; continue; }
        i += width;
        if (cp <= 0xFFFF) decoded.push_back(static_cast<char16_t>(cp));
        else {
            decoded.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            decoded.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }
    pending.erase(0, i);
    return decoded;
}

void emit_token(const llama_vocab * vocab, llama_token id, hanami::TokenDelivery * delivery) {
    if (!delivery) return;
    char piece[512];
    const int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
    if (n < 0) {
        if (delivery->error) *delivery->error = "Token decoding failed";
        return;
    }
    delivery->pending->append(piece, static_cast<size_t>(n));
    const std::u16string chunk = decode_utf8_prefix(*delivery->pending);
    if (!chunk.empty() && delivery->env) {
        deliver_piece(delivery->env, delivery->sink, delivery->on_token, *delivery->pending, chunk,
                      *delivery->error);
    }
}
} // namespace

namespace hanami {

static void append_chat_sampling_stages(llama_sampler * smpl, const llama_vocab * vocab) {
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(kChatTopK));
    const int32_t n_vocab = vocab ? llama_vocab_n_tokens(vocab) : 0;
    if (n_vocab > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                n_vocab, kChatPenaltyLastN, kChatRepeatPenalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(kChatTemp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

llama_sampler * make_chat_sampler(const llama_vocab * vocab) {
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) return nullptr;
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(kChatTopK));
    const int32_t n_vocab = vocab ? llama_vocab_n_tokens(vocab) : 0;
    if (n_vocab > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                n_vocab, kChatPenaltyLastN, kChatRepeatPenalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(kChatTemp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return smpl;
}

llama_sampler * make_chat_sampler_with_lazy_grammar(
        const llama_vocab * vocab,
        const char * grammar_str,
        const char * grammar_root,
        const char * trigger_pattern) {
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) return nullptr;
    if (vocab && grammar_str && grammar_str[0] != '\0' && trigger_pattern && trigger_pattern[0] != '\0') {
        try {
            const char * patterns[1] = { trigger_pattern };
            const char * root = (grammar_root && grammar_root[0] != '\0') ? grammar_root : "root";
            llama_sampler * grammar_smpl = llama_sampler_init_grammar_lazy_patterns(
                    vocab, grammar_str, root, patterns, 1, nullptr, 0);
            if (grammar_smpl) {
                llama_sampler_chain_add(smpl, grammar_smpl);
            }
        } catch (...) {
            // Fall back cleanly to unconstrained sampling if regex/grammar setup fails.
        }
    }
    append_chat_sampling_stages(smpl, vocab);
    return smpl;
}

size_t kv_token_count(llama_context * ctx, llama_seq_id seq_id) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (!mem) return 0;
    const llama_pos mx = llama_memory_seq_pos_max(mem, seq_id);
    if (mx < 0) return 0;
    // Hybrid/recurrent models (LFM2/LFM3) keep only the latest recurrent state cell, so
    // llama_memory_seq_pos_min(mem, seq_id) equals mx rather than 0. Hanami always fills
    // seq 0 contiguously from position 0, so mx + 1 is the true evaluated token count for
    // both pure-attention KV caches and hybrid/recurrent memories.
    return static_cast<size_t>(mx + 1);
}

bool drop_last_prompt_token_from_kv(llama_context * ctx, llama_seq_id seq_id, size_t prompt_token_count,
                                    std::string & error) {
    if (prompt_token_count == 0) {
        error = "Empty prompt for prompt lookup";
        return false;
    }
    llama_memory_t mem = llama_get_memory(ctx);
    if (!mem) {
        error = "No memory for prompt lookup";
        return false;
    }
    const size_t kv = kv_token_count(ctx, seq_id);
    if (kv == 0) {
        error = "Prompt was not evaluated before prompt lookup";
        return false;
    }
    if (kv > prompt_token_count) {
        if (!llama_memory_seq_rm(mem, seq_id, static_cast<llama_pos>(prompt_token_count), -1)) {
            error = "Cannot trim KV tail before prompt lookup";
            return false;
        }
    }
    const size_t kv2 = kv_token_count(ctx, seq_id);
    if (kv2 == prompt_token_count - 1) {
        return true;
    }
    if (kv2 != prompt_token_count) {
        error = "Prompt KV length does not match tokenizer output";
        return false;
    }
    const llama_pos last_pos = static_cast<llama_pos>(prompt_token_count - 1);
    if (!llama_memory_seq_rm(mem, seq_id, last_pos, -1)) {
        error = "Cannot trim last prompt token for prompt lookup (hybrid memory)";
        return false;
    }
    if (kv_token_count(ctx, seq_id) != prompt_token_count - 1) {
        error = "Prompt lookup KV trim failed";
        return false;
    }
    return true;
}

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
        std::string & error) {
    SamplerPtr sampler(make_chat_sampler(vocab));
    if (!sampler) {
        error = "Failed to initialize sampler";
        return false;
    }

    auto setup_one = [&](llama_token tok, int pos) {
        llama_batch_ext_clear(batch);
        const int index = llama_batch_ext_add_token(batch, 0, tok);
        const llama_pos positions[4] = { pos, 0, 0, 0 };
        llama_batch_ext_set_pos(batch, index, positions);
        llama_batch_ext_set_output_logits(batch, index, true);
        (void) mrope;
    };

    int position = start_position;
    generated.clear();
    for (int i = 0; i < max_new_tokens && position + i < context_tokens; ++i) {
        if (cancelled && cancelled->load()) {
            error.clear();
            return false;
        }
        const llama_token next = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        llama_sampler_accept(sampler.get(), next);
        generated.push_back(next);
        if (has_verbatim_token_loop(generated)) break;
        if (position + static_cast<int>(generated.size()) >= context_tokens) break;
        if (static_cast<int>(generated.size()) >= max_new_tokens) break;
        setup_one(next, position + static_cast<int>(generated.size()) - 1);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            error = "Model failed while decoding response";
            return false;
        }
    }
    return error.empty();
}

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
        std::string & error) {
    if (prompt_tokens.empty()) {
        error = "Empty prompt for speculative decode";
        return false;
    }

    const llama_model * model = llama_get_model(ctx);
    const bool is_hybrid_or_recr = model && (llama_model_is_hybrid(model) || llama_model_is_recurrent(model));
    const int max_rs_draft = is_hybrid_or_recr ? static_cast<int>(llama_n_rs_seq(ctx)) : kNgramMaxDraft;
    if (is_hybrid_or_recr && max_rs_draft < kNgramMinDraft) {
        error = "Cannot trim last prompt token for prompt lookup (hybrid memory)";
        return false;
    }

    if (!drop_last_prompt_token_from_kv(ctx, 0, prompt_tokens.size(), error)) {
        return false;
    }
    if (stats) {
        stats->n_prompt_tail_duplicate = 0;
        if (kv_token_count(ctx, 0) + 1 != prompt_tokens.size()) {
            stats->n_prompt_tail_duplicate = 1;
            error = "Last prompt token was still present in KV before prompt lookup";
            return false;
        }
    }

    SamplerPtr smpl(make_chat_sampler(vocab));
    if (!smpl) {
        error = "Failed to initialize sampler for prompt lookup";
        return false;
    }
    BatchPtr batch_tgt(llama_batch_ext_init(ctx));
    if (!batch_tgt) {
        error = "Failed to allocate decode batch for prompt lookup";
        return false;
    }

    (void) mrope;
    const llama_seq_id seq_id = 0;
    const int max_batch_draft = std::min(
            max_rs_draft, std::max(0, static_cast<int>(llama_n_batch(ctx)) - 1));

    std::vector<llama_token> prompt_tgt;
    if (prompt_tokens.size() > 1) {
        prompt_tgt.assign(prompt_tokens.begin(), prompt_tokens.end() - 1);
    }

    llama_token id_last = prompt_tokens.back();
    int n_past = static_cast<int>(prompt_tokens.size()) - 1;

    NgramModDrafter drafter;
    drafter.begin(prompt_tgt);

    std::vector<llama_token> draft;
    bool has_eos = false;
    generated.clear();
    llama_memory_t mem = llama_get_memory(ctx);

    auto trim_kv_tail = [&](int from_pos) -> bool {
        if (!mem) return false;
        const size_t cur_kv = kv_token_count(ctx, seq_id);
        if (from_pos >= 0 && static_cast<size_t>(from_pos) >= cur_kv) {
            return true;
        }
        return llama_memory_seq_rm(mem, seq_id, from_pos, -1);
    };

    auto add_token = [&](llama_token tok, int pos) {
        const int32_t idx = llama_batch_ext_add_token(batch_tgt.get(), seq_id, tok);
        const llama_pos positions[4] = { pos, 0, 0, 0 };
        llama_batch_ext_set_pos(batch_tgt.get(), idx, positions);
        llama_batch_ext_set_output_logits(batch_tgt.get(), idx, true);
    };

    auto finish_session = [&]() {
        if (!session_tokens) return;
        trim_kv_tail(n_past);
        const size_t kv = kv_token_count(ctx, seq_id);
        const size_t np = static_cast<size_t>(n_past);
        auto build_layout = [&](bool full_prompt) {
            std::vector<llama_token> layout;
            if (full_prompt) {
                layout = prompt_tokens;
            } else if (prompt_tokens.size() > 1) {
                layout.assign(prompt_tokens.begin(), prompt_tokens.end() - 1);
            } else {
                layout = prompt_tokens;
            }
            layout.insert(layout.end(), generated.begin(), generated.end());
            if (layout.size() > np) layout.resize(np);
            return layout;
        };
        for (const bool full_prompt : {true, false}) {
            std::vector<llama_token> layout = build_layout(full_prompt);
            if (layout.size() == kv && kv == np) {
                *session_tokens = std::move(layout);
                return;
            }
        }
        *session_tokens = build_layout(false);
        if (session_tokens->empty() && np == 0) return;
        if (session_tokens->size() != np) *session_tokens = build_layout(true);
    };

    while (!has_eos && static_cast<int>(generated.size()) < max_new_tokens) {
        if (cancelled && cancelled->load()) {
            error.clear();
            return false;
        }

        int n_draft_max = context_tokens - n_past - 2;
        n_draft_max = std::min(n_draft_max, max_new_tokens - static_cast<int>(generated.size()) - 1);
        n_draft_max = std::max(0, std::min(n_draft_max, max_batch_draft));
        drafter.draft(id_last, prompt_tgt, n_draft_max, draft);

        llama_batch_ext_clear(batch_tgt.get());
        const int batch_id_last_pos = n_past;
        add_token(id_last, batch_id_last_pos);
        if (stats) stats->n_eval_tokens += 1;
        n_past += 1;
        for (size_t i = 0; i < draft.size(); ++i) {
            add_token(draft[i], n_past + static_cast<int>(i));
            if (stats) stats->n_eval_tokens += 1;
        }

        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch_tgt.get()) != 0) {
            error = "Model failed while decoding response";
            break;
        }

        const size_t n_draft = draft.size();
        std::vector<llama_token> ids = sample_and_accept_n(smpl.get(), ctx, vocab, draft);
        if (ids.empty()) {
            error = "Sampling failed during speculation";
            break;
        }

        const size_t n_accepted = ids.size() - 1;
        if (stats) {
            stats->n_drafted += static_cast<int>(n_draft);
            stats->n_accept += static_cast<int>(n_accepted);
        }

        drafter.accept(n_accepted);

        const int n_committed = static_cast<int>(ids.size());
        int n_output = 0;
        for (int i = 0; i < n_committed; ++i) {
            const llama_token t = ids[static_cast<size_t>(i)];
            if (llama_vocab_is_eog(vocab, t)) {
                has_eos = true;
                n_past = batch_id_last_pos + i;
                trim_kv_tail(n_past);
                break;
            }
            prompt_tgt.push_back(id_last);
            id_last = t;
            generated.push_back(t);
            n_output += 1;

            if (delivery && delivery->env) {
                emit_token(vocab, t, delivery);
                if (delivery->error && !delivery->error->empty()) break;
            }

            if (static_cast<int>(generated.size()) >= max_new_tokens
                    || has_verbatim_token_loop(generated)) {
                has_eos = true;
                n_past = batch_id_last_pos + i + 1;
                trim_kv_tail(n_past);
                break;
            }
        }

        if (!has_eos) {
            n_past = batch_id_last_pos + n_committed;
            if (!trim_kv_tail(n_past)) {
                error = "Cannot trim KV tail during prompt lookup (hybrid memory)";
                break;
            }
        }

        draft.clear();

        if (stats) stats->n_predict += n_output;
    }

    if (stats) stats->n_predict = static_cast<int>(generated.size());

    finish_session();

    return error.empty();
}

} // namespace hanami