#include "speculative_decode.h"
#include "speculative.h"

#include <jni.h>
#include <algorithm>
#include <mutex>

namespace {
std::once_flag g_common_init_once;

void ensure_common_init() {
    std::call_once(g_common_init_once, [] { common_init(); });
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
  if (!chunk.empty() && delivery->env)
        deliver_piece(delivery->env, delivery->sink, delivery->on_token, *delivery->pending, chunk,
                      *delivery->error);
}
} // namespace

namespace hanami {

size_t kv_token_count(llama_context * ctx, llama_seq_id seq_id) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (!mem) return 0;
    const llama_pos mn = llama_memory_seq_pos_min(mem, seq_id);
    const llama_pos mx = llama_memory_seq_pos_max(mem, seq_id);
    if (mn < 0 || mx < 0) return 0;
    return static_cast<size_t>(mx - mn + 1);
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
    if (kv != prompt_token_count) {
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

void configure_phone_ngram_spec(common_params_speculative & spec) {
    spec.types = { COMMON_SPECULATIVE_TYPE_NGRAM_MOD };
    spec.ngram_mod.n_match = 16;
    spec.ngram_mod.n_max = 12;
    spec.ngram_mod.n_min = 2;
}

common_sampler * make_chat_sampler(const llama_model * model) {
    ensure_common_init();
    common_params_sampling sp;
    sp.samplers = { COMMON_SAMPLER_TYPE_TOP_K, COMMON_SAMPLER_TYPE_TEMPERATURE };
    sp.top_k = 50;
    sp.temp = 0.1f;
    sp.seed = LLAMA_DEFAULT_SEED;
    return common_sampler_init(model, sp);
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
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

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
            llama_sampler_free(sampler);
            return false;
        }
        const llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        llama_sampler_accept(sampler, next);
        generated.push_back(next);
        if (position + static_cast<int>(generated.size()) >= context_tokens) break;
        if (static_cast<int>(generated.size()) >= max_new_tokens) break;
        setup_one(next, position + static_cast<int>(generated.size()) - 1);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            error = "Model failed while decoding response";
            llama_sampler_free(sampler);
            return false;
        }
    }
    llama_sampler_free(sampler);
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
    ensure_common_init();

    if (prompt_tokens.empty()) {
        error = "Empty prompt for speculative decode";
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

    common_params_speculative params_spec;
    configure_phone_ngram_spec(params_spec);

    common_speculative * spec = common_speculative_init(params_spec, 1);
    if (!spec) {
        error = "Failed to initialize ngram-mod speculation";
        return false;
    }

    const llama_model * model = llama_get_model(ctx);
    common_sampler_ptr smpl(make_chat_sampler(model));

    const bool use_ckpt_tgt =
            common_context_can_seq_rm(ctx) == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    (void) mrope;

    const llama_seq_id seq_id = 0;
    llama_tokens prompt_tgt;
    if (prompt_tokens.size() > 1) {
        prompt_tgt.assign(prompt_tokens.begin(), prompt_tokens.end() - 1);
    }

    llama_token id_last = prompt_tokens.back();
    int n_past = static_cast<int>(prompt_tokens.size()) - 1;

    common_speculative_begin(spec, seq_id, prompt_tgt);

    common_batch batch_tgt(ctx);
    llama_tokens draft;
    common_prompt_checkpoint ckpt;

    bool has_eos = false;
    generated.clear();
    llama_memory_t mem = llama_get_memory(ctx);

    auto trim_kv_tail = [&](int from_pos) {
        if (mem) llama_memory_seq_rm(mem, seq_id, from_pos, -1);
    };

    auto finish_session = [&]() {
        if (!session_tokens) return;
        const size_t kv = kv_token_count(ctx, seq_id);
        // KV holds prompt[0..P-2] after tail trim, then id_last + generation through speculation.
        // Token id list for reuse is prompt[P-1] re-established on the first spec batch, then outputs.
        session_tokens->clear();
        if (prompt_tokens.size() > 1) {
            session_tokens->insert(session_tokens->end(), prompt_tokens.begin(),
                                   prompt_tokens.end() - 1);
        }
        session_tokens->insert(session_tokens->end(), generated.begin(), generated.end());
        if (session_tokens->size() != kv) {
            // Full prompt + generated (when the tail token remains in KV as a single slot).
            std::vector<llama_token> full = prompt_tokens;
            full.insert(full.end(), generated.begin(), generated.end());
            if (full.size() == kv) {
                *session_tokens = std::move(full);
            } else {
                error = "KV cache does not match session token list after prompt lookup";
            }
        }
    };

    while (!has_eos && static_cast<int>(generated.size()) < max_new_tokens) {
        if (cancelled && cancelled->load()) {
            error.clear();
            common_speculative_free(spec);
            return false;
        }

        if (draft.empty()) {
            if (use_ckpt_tgt) {
                ckpt.update_pos(
                        static_cast<int64_t>(prompt_tgt.size() + 1),
                        llama_memory_seq_pos_min(mem, seq_id),
                        llama_memory_seq_pos_max(mem, seq_id));
            }

            int n_draft_max = context_tokens - n_past - 2;
            n_draft_max = std::max(0, std::min(n_draft_max, max_new_tokens - static_cast<int>(generated.size()) - 1));

            common_speculative_get_draft_params(spec, seq_id) = {
                /* .drafting   = */ true,
                /* .n_max      = */ n_draft_max,
                /* .pos0       = */ n_past,
                /* .id_last    = */ id_last,
                /* .prompt     = */ &prompt_tgt,
                /* .result     = */ &draft,
            };
            common_speculative_draft(spec);

            if (!draft.empty() && use_ckpt_tgt) {
                ckpt.update_tgt(ctx, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
            }
        }

        batch_tgt.clear();
        const int batch_id_last_pos = n_past;
        batch_tgt.add(id_last, batch_id_last_pos, seq_id, true);
        if (stats) stats->n_eval_tokens += 1;
        n_past += 1;
        for (size_t i = 0; i < draft.size(); ++i) {
            batch_tgt.add(draft[i], n_past + static_cast<int>(i), seq_id, true);
            if (stats) stats->n_eval_tokens += 1;
        }

        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch_tgt.get()) != 0) {
            error = "Model failed while decoding response";
            break;
        }

        if (!common_speculative_process(spec, batch_tgt)) {
            error = "Speculative state update failed";
            break;
        }

        common_sampler_ptr smpl_save;
        if (use_ckpt_tgt) {
            smpl_save.reset(common_sampler_clone(smpl.get()));
        }

        const size_t n_draft = draft.size();
        auto ids = common_sampler_sample_and_accept_n(smpl.get(), ctx, draft);
        if (ids.empty()) {
            error = "Sampling failed during speculation";
            break;
        }

        if (stats) {
            stats->n_drafted += static_cast<int>(n_draft);
            stats->n_accept += static_cast<int>(ids.size()) - 1;
        }

        if (use_ckpt_tgt && ids.size() - 1 < n_draft) {
            draft = std::move(ids);
            GGML_ASSERT(ckpt.load_tgt(ctx, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
            llama_memory_seq_rm(mem, seq_id, ckpt.pos_max + 1, -1);
            prompt_tgt.resize(static_cast<size_t>(ckpt.n_tokens));
            smpl = std::move(smpl_save);
            n_past = static_cast<int>(prompt_tgt.size());
            continue;
        }

        common_speculative_accept(spec, seq_id, static_cast<uint16_t>(ids.size() - 1));

        const int n_committed = static_cast<int>(ids.size());
        int n_output = 0;
        for (int i = 0; i < n_committed; ++i) {
            const llama_token t = ids[static_cast<size_t>(i)];
            if (llama_vocab_is_eog(vocab, t)) {
                has_eos = true;
                trim_kv_tail(batch_id_last_pos + i);
                n_past = batch_id_last_pos + i;
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

            if (static_cast<int>(generated.size()) >= max_new_tokens) {
                has_eos = true;
                n_past = batch_id_last_pos + i + 1;
                trim_kv_tail(n_past);
                break;
            }
        }

        if (!has_eos) {
            n_past = batch_id_last_pos + n_committed;
            trim_kv_tail(n_past);
        }

        draft.clear();

        if (stats) stats->n_predict += n_output;
    }

    common_speculative_free(spec);

    if (stats) stats->n_predict = static_cast<int>(generated.size());

    finish_session();

    return error.empty();
}

} // namespace hanami