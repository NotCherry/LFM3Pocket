#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <string>
#include <thread>
#include <vector>

namespace {
std::once_flag backend_once;

struct ModelHandle {
    llama_model * model = nullptr;
    mtmd_context * multimodal = nullptr;
    // Persistent text session (PocketPal / ChatterUI keep a live context for KV reuse).
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    std::vector<llama_token> cached_tokens;
    int ctx_tokens = 0;
    int threads = 0;
    bool media_dirty = false;
    std::atomic<bool> cancelled{false};
    std::mutex decode_mutex;

    void release_session() {
        if (batch) {
            llama_batch_ext_free(batch);
            batch = nullptr;
        }
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        cached_tokens.clear();
        ctx_tokens = 0;
        threads = 0;
        media_dirty = false;
    }

    ~ModelHandle() {
        release_session();
        if (multimodal) mtmd_free(multimodal);
        if (model) llama_model_free(model);
    }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<ModelHandle>> registry;
jlong next_handle = 1;

std::shared_ptr<ModelHandle> find_model(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = registry.find(id);
    return found == registry.end() ? nullptr : found->second;
}

void setup_batch(llama_batch_ext * batch, const llama_token * tokens, int count,
                 llama_pos start, bool /*mrope*/) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const int index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos positions[4] = { start + i, 0, 0, 0 };
        llama_batch_ext_set_pos(batch, index, positions);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

jstring make_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

int32_t choose_batch_size(int context_tokens) {
    // ChatterUI / PocketPal default n_batch=512. Prefer 1024 when context allows for
    // faster prompt processing (llama-bench style) without exceeding n_ctx.
    if (context_tokens >= 1024) return 1024;
    return std::max(64, context_tokens / 2);
}

llama_context_params make_ctx_params(ModelHandle * state, int context_tokens, int threads) {
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.abort_callback = [](void * data) {
        return static_cast<std::atomic<bool> *>(data)->load();
    };
    ctx_params.abort_callback_data = &state->cancelled;
    ctx_params.n_ctx = context_tokens;
    const int32_t batch = choose_batch_size(context_tokens);
    ctx_params.n_batch = batch;
    ctx_params.n_ubatch = batch;
    // flash_attn_type stays at llama.cpp default (AUTO); do not set it redundantly.
    const unsigned cpu = std::thread::hardware_concurrency();
    ctx_params.n_threads = static_cast<int32_t>(
            std::min(static_cast<unsigned>(threads), std::max(1u, cpu)));
    ctx_params.n_threads_batch = ctx_params.n_threads;
    return ctx_params;
}

bool ensure_text_session(ModelHandle * state, int context_tokens, int threads, std::string & error) {
    if (state->ctx && !state->media_dirty
            && state->ctx_tokens == context_tokens && state->threads == threads) {
        return true;
    }
    state->release_session();
    llama_context_params ctx_params = make_ctx_params(state, context_tokens, threads);
    state->ctx = llama_init_from_model(state->model, ctx_params);
    if (!state->ctx) {
        error = "Unable to allocate context; insufficient RAM?";
        return false;
    }
    state->batch = llama_batch_ext_init(state->ctx);
    if (!state->batch) {
        state->release_session();
        error = "Cannot allocate inference batch";
        return false;
    }
    state->ctx_tokens = context_tokens;
    state->threads = threads;
    state->media_dirty = false;
    return true;
}

size_t common_prefix_len(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) ++i;
    return i;
}

// Returns false on decode error or cancel. On cancel, sets error empty and
// cancelled stays true — caller must release_session() so cached_tokens cannot
// claim a partially evaluated prompt.
bool eval_tokens(ModelHandle * state, const llama_token * tokens, int count, int start_pos,
                 bool mrope, std::string & error) {
    int position = start_pos;
    int remaining = count;
    const int chunk = static_cast<int>(choose_batch_size(state->ctx_tokens));
    while (remaining > 0) {
        if (state->cancelled.load()) {
            error.clear();
            return false;
        }
        int n = std::min(chunk, remaining);
        setup_batch(state->batch, tokens + (position - start_pos), n, position, mrope);
        if (llama_process(state->ctx, LLAMA_PROCESS_TYPE_DECODE, state->batch) != 0) {
            error = "Model failed while evaluating the prompt";
            return false;
        }
        position += n;
        remaining -= n;
    }
    return true;
}

// Drop positions [shared, ∞). LFM2/LFM3 are hybrid — partial seq_rm may fail.
// On failure, clear all memory and return false so the caller re-prefills from 0.
bool trim_cached_prefix(ModelHandle * state, size_t shared) {
    llama_memory_t mem = llama_get_memory(state->ctx);
    if (!mem) {
        state->cached_tokens.clear();
        return false;
    }
    if (shared >= state->cached_tokens.size()) {
        return true;
    }
    if (llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(shared), -1)) {
        state->cached_tokens.resize(shared);
        return true;
    }
    llama_memory_clear(mem, true);
    state->cached_tokens.clear();
    return false;
}

// Convert complete UTF-8 codepoints into JNI UTF-16; carry incomplete bytes
// across tokenizer piece boundaries (important for Polish text and emoji).
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
        if (!valid || (width == 3 && cp < 0x800) || (width == 4 && cp < 0x10000)
            || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF) {
            decoded.push_back(u'\uFFFD');
            ++i;
            continue;
        }
        if (cp <= 0xFFFF) {
            decoded.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            decoded.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            decoded.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
        i += width;
    }
    pending.erase(0, i);
    return decoded;
}

void deliver(JNIEnv * env, jobject sink, jmethodID method, const std::u16string & text) {
    if (text.empty()) return;
    jstring piece = env->NewString(reinterpret_cast<const jchar *>(text.data()),
                                   static_cast<jsize>(text.size()));
    if (piece) {
        env->CallVoidMethod(sink, method, piece);
        env->DeleteLocalRef(piece);
    }
}

int recommended_mm_threads() {
    const unsigned cpu = std::thread::hardware_concurrency();
    if (cpu <= 1) return 1;
    if (cpu <= 4) return static_cast<int>(cpu);
    return static_cast<int>(std::min(6u, static_cast<unsigned>(cpu * 0.8)));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_loadModel(
        JNIEnv * env, jclass, jstring path, jstring mmproj_path) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    const char * raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return 0;
    std::string filename(raw);
    env->ReleaseStringUTFChars(path, raw);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    // AUTO → mmap on Android (llama.cpp default). ChatterUI also enables mlock;
    // Hanami intentionally skips mlock so the LMK can reclaim idle weight pages
    // (see docs/plans/background-mode.md).
    params.load_mode = LLAMA_LOAD_MODE_AUTO;
    auto state = std::make_shared<ModelHandle>();
    state->model = llama_model_load_from_file(filename.c_str(), params);
    if (!state->model) {
        return 0;
    }
    if (mmproj_path && env->GetStringLength(mmproj_path) > 0) {
        const char * raw_mmproj = env->GetStringUTFChars(mmproj_path, nullptr);
        if (!raw_mmproj) {
            return 0;
        }
        mtmd_context_params mm_params = mtmd_context_params_default();
        mm_params.use_gpu = false;
        mm_params.n_threads = recommended_mm_threads();
        state->multimodal = mtmd_init_from_file(raw_mmproj, state->model, mm_params);
        env->ReleaseStringUTFChars(mmproj_path, raw_mmproj);
        if (!state->multimodal) {
            return 0;
        }
    }
    std::lock_guard<std::mutex> lock(registry_mutex);
    const jlong id = next_handle++;
    registry.emplace(id, std::move(state));
    return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_modelSupportsAudioIn(
        JNIEnv *, jclass, jlong handle) {
    auto state = find_model(handle);
    if (!state || !state->multimodal) {
        return JNI_FALSE;
    }
    return mtmd_support_audio(state->multimodal) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_unloadModel(
        JNIEnv *, jclass, jlong handle) {
    std::shared_ptr<ModelHandle> state;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = registry.find(handle);
        if (found == registry.end()) return;
        state = found->second;
        state->cancelled.store(true);
        registry.erase(found);
    }
    // In-flight decode retains its own reference; destruction follows its return.
}

extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_requestStop(JNIEnv *, jclass, jlong handle) {
    if (auto state = find_model(handle)) state->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_resetModelStop(JNIEnv *, jclass, jlong handle) {
    if (auto state = find_model(handle)) state->cancelled.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_releaseSession(JNIEnv *, jclass, jlong handle) {
    if (auto state = find_model(handle)) {
        std::lock_guard<std::mutex> lock(state->decode_mutex);
        state->release_session();
    }
}

// Legacy process-wide methods retained for the original smoke-test API only.
// Production adapters exclusively call the model-scoped methods above.
extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_stop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    for (auto & item : registry) item.second->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_resetStop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    for (auto & item : registry) item.second->cancelled.store(false);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_nativeBuildInfo(JNIEnv * env, jclass) {
#if defined(HANAMI_ARM_DOTPROD) || defined(__ARM_FEATURE_DOTPROD)
    const char * arch = "arm64-v8a march=armv8.2-a+dotprod";
#elif defined(__aarch64__)
    const char * arch = "arm64-v8a (baseline; DOTPROD expected on phone flavor)";
#elif defined(__x86_64__)
    const char * arch = "x86_64";
#else
    const char * arch = "unknown";
#endif
    std::string info = std::string("Hanami native | ") + arch +
            " | -O3 | GGML_NATIVE=OFF | GGML_OPENMP=OFF | GGML_CPU_KLEIDIAI=OFF"
            " | flash_attn=auto | n_batch<=1024 | KV reuse=on";
    return make_string(env, info);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_generateUtf8(
        JNIEnv * env, jclass, jlong handle, jbyteArray prompt, jobjectArray media_paths,
        jint max_tokens, jint context_tokens, jint threads, jobject sink) {
    if (!handle) return make_string(env, "Model has not been loaded");
    if (!prompt || !sink) return make_string(env, "Missing prompt or callback");
    if (context_tokens < 512 || context_tokens > 8192 || context_tokens % 256 != 0 ||
        threads < 1 || threads > 8 || max_tokens < 1 || max_tokens > 2048 || max_tokens >= context_tokens)
        return make_string(env, "Invalid inference resource limits");
    auto state = find_model(handle);
    if (!state) return make_string(env, "Model handle is retired or invalid");
    std::unique_lock<std::mutex> decode_lock(state->decode_mutex, std::try_to_lock);
    if (!decode_lock.owns_lock()) return make_string(env, "Model is already generating");
    // Do not clear cancelled here — ChatSession/ModelRuntime call resetStop first.
    // Clearing would drop a Stop that raced ahead of this entry.
    if (state->cancelled.load()) return nullptr;
    llama_model * model = state->model;
    const bool mrope = state->multimodal && mtmd_decode_use_mrope(state->multimodal);
    const llama_vocab * vocab = llama_model_get_vocab(model);

    const jsize input_size = env->GetArrayLength(prompt);
    std::string input(static_cast<size_t>(input_size), '\0');
    if (input_size > 0) env->GetByteArrayRegion(prompt, 0, input_size,
            reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return nullptr; // Propagate JNI allocation/access failures to Java.

    int count = llama_tokenize(vocab, input.data(), static_cast<int32_t>(input.size()),
                               nullptr, 0, false, true);
    if (count < 0) count = -count;
    if (count <= 0) return make_string(env, "Prompt tokenizer returned no tokens");
    if (count + 1 >= context_tokens) return make_string(env, "Prompt is too long for the selected context");

    std::vector<llama_token> tokens(count);
    int converted = llama_tokenize(vocab, input.data(), static_cast<int32_t>(input.size()),
                                    tokens.data(), count, false, true);
    if (converted < 0) return make_string(env, "Failed to tokenize prompt");
    tokens.resize(static_cast<size_t>(converted));

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID callback = sink_class ? env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (!callback && env->ExceptionCheck()) env->ExceptionClear();
    if (sink_class) env->DeleteLocalRef(sink_class);

    std::string error;
    std::string pending;
    bool prompt_ready = false;
    if (!callback) {
        error = "Cannot locate Java token callback";
    }

    const jsize media_count = media_paths ? env->GetArrayLength(media_paths) : 0;
    const bool has_media = media_count > 0;
    int position = 0;

    if (error.empty() && has_media) {
        // Media turns rebuild the graph; drop any text KV session afterwards.
        state->release_session();
        llama_context_params ctx_params = make_ctx_params(state.get(), context_tokens, threads);
        state->ctx = llama_init_from_model(model, ctx_params);
        if (!state->ctx) {
            error = "Unable to allocate context; insufficient RAM?";
        } else {
            state->batch = llama_batch_ext_init(state->ctx);
            if (!state->batch) {
                state->release_session();
                error = "Cannot allocate inference batch";
            } else {
                state->ctx_tokens = context_tokens;
                state->threads = threads;
                state->media_dirty = true;
            }
        }
        if (error.empty()) {
            if (!state->multimodal) {
                error = "This model has no multimodal projector loaded";
            } else {
                std::vector<mtmd_helper_bitmap_wrapper> wrappers;
                std::vector<const mtmd_bitmap *> bitmaps;
                wrappers.reserve(static_cast<size_t>(media_count));
                bitmaps.reserve(static_cast<size_t>(media_count));
                for (jsize i = 0; i < media_count && error.empty(); ++i) {
                    auto path = static_cast<jstring>(env->GetObjectArrayElement(media_paths, i));
                    if (!path) {
                        error = "A selected media path is missing";
                        break;
                    }
                    const char * raw_media = env->GetStringUTFChars(path, nullptr);
                    if (!raw_media) {
                        env->DeleteLocalRef(path);
                        error = "Could not read a selected media path";
                        break;
                    }
                    mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_file(
                            state->multimodal, raw_media, false, mtmd_helper_init_opt_default());
                    env->ReleaseStringUTFChars(path, raw_media);
                    env->DeleteLocalRef(path);
                    if (!wrapper.bitmap) {
                        if (wrapper.video_ctx) mtmd_helper_video_free(wrapper.video_ctx);
                        error = "Could not read the selected image or audio file";
                        break;
                    }
                    wrappers.push_back(wrapper);
                    bitmaps.push_back(wrapper.bitmap);
                }
                if (error.empty()) {
                    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
                    mtmd_input_text text = { input.data(), input.size(), false, true };
                    if (!chunks || mtmd_tokenize(state->multimodal, chunks, &text,
                                                  bitmaps.data(), bitmaps.size()) != 0) {
                        error = "Could not prepare the selected media for LFM3";
                    } else {
                        llama_pos next_position = 0;
                        if (mtmd_helper_eval_chunks(state->multimodal, state->ctx, chunks, 0, 0,
                                                    choose_batch_size(context_tokens), true,
                                                    &next_position) != 0) {
                            error = "Model failed while evaluating the selected media";
                        } else {
                            position = static_cast<int>(next_position);
                            if (position + 1 >= context_tokens) {
                                error = "Selected media is too large for the selected phone context";
                            }
                        }
                    }
                    if (chunks) mtmd_input_chunks_free(chunks);
                }
                for (auto & wrapper : wrappers) {
                    if (wrapper.bitmap) mtmd_bitmap_free(wrapper.bitmap);
                    if (wrapper.video_ctx) mtmd_helper_video_free(wrapper.video_ctx);
                }
            }
        }
        // Do not reuse media KV for later text turns.
        state->cached_tokens.clear();
        prompt_ready = error.empty() && state->ctx != nullptr;
    } else if (error.empty()) {
        if (!ensure_text_session(state.get(), context_tokens, threads, error)) {
            // error set
        } else {
            size_t shared = common_prefix_len(state->cached_tokens, tokens);
            // Need at least one token re-eval to refresh logits when the prompt is
            // a strict prefix of the previous session (llama.rn slot reuse pattern).
            if (shared == tokens.size() && shared > 0) {
                shared -= 1;
            }
            if (shared < state->cached_tokens.size()) {
                // Hybrid LFM memory may reject partial seq_rm — then full re-prefill.
                if (!trim_cached_prefix(state.get(), shared)) {
                    shared = 0;
                }
            }
            position = static_cast<int>(shared);
            if (position < converted) {
                if (!eval_tokens(state.get(), tokens.data() + position, converted - position,
                                 position, mrope, error)) {
                    // Cancel or decode error: drop partial session so the next turn
                    // cannot reuse a half-evaluated prompt (blocking review #1).
                    state->release_session();
                } else {
                    position = converted;
                    prompt_ready = true;
                }
            } else {
                prompt_ready = state->ctx != nullptr;
            }
        }
    }

    llama_sampler * sampler = nullptr;
    if (error.empty() && prompt_ready && state->ctx && !state->cancelled.load()) {
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::vector<llama_token> generated;
    for (int i = 0; error.empty() && sampler && !state->cancelled.load() &&
                    i < max_tokens && position + i < context_tokens; ++i) {
        const llama_token next = llama_sampler_sample(sampler, state->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        llama_sampler_accept(sampler, next);

        char piece[512];
        const int n = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (n < 0) { error = "Token decoding failed"; break; }
        pending.append(piece, static_cast<size_t>(n));
        deliver(env, sink, callback, decode_utf8_prefix(pending));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            error = "Java callback failed";
            break;
        }

        // Always write the accepted token into KV before stopping on max_tokens
        // so cached_tokens cannot outrun the memory (blocking review #2).
        if (position + static_cast<int>(generated.size()) + 1 >= context_tokens) {
            break; // no room to store this token
        }
        setup_batch(state->batch, &next, 1, position + static_cast<int>(generated.size()), mrope);
        if (llama_process(state->ctx, LLAMA_PROCESS_TYPE_DECODE, state->batch) != 0) {
            error = "Model failed while decoding response";
            break;
        }
        generated.push_back(next);
        if (static_cast<int>(generated.size()) >= max_tokens) break;
    }
    if (!pending.empty() && error.empty() && !state->cancelled.load())
        deliver(env, sink, callback, u"\uFFFD");

    if (sampler) llama_sampler_free(sampler);

    if (state->cancelled.load() || !error.empty()) {
        // Cancel / error mid-turn leaves memory inconsistent with any cache claim.
        state->release_session();
    } else if (!has_media && state->ctx && !state->media_dirty) {
        // Keep prompt + generated tokens that are actually in KV.
        state->cached_tokens = tokens;
        state->cached_tokens.insert(state->cached_tokens.end(), generated.begin(), generated.end());
    } else {
        state->release_session();
    }

    return error.empty() || state->cancelled.load() ? nullptr : make_string(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_runBenchmarkUtf8(
        JNIEnv * env, jclass, jlong handle, jint pp_tokens, jint tg_tokens,
        jint context_tokens, jint threads) {
    if (!handle) return make_string(env, "Model has not been loaded");
    if (context_tokens < 512 || context_tokens > 8192 || context_tokens % 256 != 0 ||
        threads < 1 || threads > 8 || pp_tokens < 16 || pp_tokens > 2048 ||
        tg_tokens < 8 || tg_tokens > 512 || pp_tokens + tg_tokens + 8 >= context_tokens)
        return make_string(env, "Invalid benchmark limits");
    auto state = find_model(handle);
    if (!state) return make_string(env, "Model handle is retired or invalid");
    std::unique_lock<std::mutex> decode_lock(state->decode_mutex, std::try_to_lock);
    if (!decode_lock.owns_lock()) return make_string(env, "Model is already generating");
    if (state->cancelled.load()) return make_string(env, "Benchmark cancelled");
    // Free the chat session first so peak RAM is one context, not two.
    state->release_session();

    llama_context_params ctx_params = make_ctx_params(state.get(), context_tokens, threads);
    llama_context * ctx = llama_init_from_model(state->model, ctx_params);
    if (!ctx) return make_string(env, "Unable to allocate benchmark context; insufficient RAM?");
    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    if (!batch) {
        llama_free(ctx);
        return make_string(env, "Cannot allocate benchmark batch");
    }

    const llama_vocab * vocab = llama_model_get_vocab(state->model);
    const llama_token bos = llama_vocab_bos(vocab);
    std::vector<llama_token> tokens(static_cast<size_t>(pp_tokens),
                                    bos != LLAMA_TOKEN_NULL ? bos : static_cast<llama_token>(1));

    auto eval_chunked = [&](const llama_token * data, int count, int start_pos) -> bool {
        int position = start_pos;
        int remaining = count;
        const int chunk = static_cast<int>(choose_batch_size(context_tokens));
        while (remaining > 0 && !state->cancelled.load()) {
            int n = std::min(chunk, remaining);
            setup_batch(batch, data + (position - start_pos), n, position, false);
            if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return false;
            position += n;
            remaining -= n;
        }
        return !state->cancelled.load();
    };

    using clock = std::chrono::steady_clock;
    const auto pp_start = clock::now();
    if (!eval_chunked(tokens.data(), pp_tokens, 0)) {
        llama_batch_ext_free(batch);
        llama_free(ctx);
        return make_string(env, state->cancelled.load() ? "Benchmark cancelled" : "Prompt processing failed");
    }
    const auto pp_end = clock::now();
    const double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    const double pp_tps = pp_ms > 0.0 ? (pp_tokens * 1000.0 / pp_ms) : 0.0;

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int decoded = 0;
    int position = pp_tokens;
    const auto tg_start = clock::now();
    std::string error;
    for (int i = 0; i < tg_tokens && position + i < context_tokens && !state->cancelled.load(); ++i) {
        const llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        llama_sampler_accept(sampler, next);
        ++decoded;
        if (i + 1 == tg_tokens || position + i + 1 >= context_tokens) break;
        setup_batch(batch, &next, 1, position + i, false);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            error = "Decode failed";
            break;
        }
    }
    const auto tg_end = clock::now();
    const double tg_ms = std::chrono::duration<double, std::milli>(tg_end - tg_start).count();
    const double tg_tps = (tg_ms > 0.0 && decoded > 0) ? (decoded * 1000.0 / tg_ms) : 0.0;

    llama_sampler_free(sampler);
    llama_batch_ext_free(batch);
    llama_free(ctx);

    if (!error.empty()) return make_string(env, error);
    if (state->cancelled.load()) return make_string(env, "Benchmark cancelled");

    char line[512];
    std::snprintf(line, sizeof(line),
            "Hanami llama-bench\n"
            "pp %d tokens: %.2f tok/s (%.0f ms)\n"
            "tg %d tokens: %.2f tok/s (%.0f ms)\n"
            "context=%d threads=%d n_batch=%d\n"
            "build: see NativeBridge.nativeBuildInfo()",
            pp_tokens, pp_tps, pp_ms, decoded, tg_tps, tg_ms,
            context_tokens, threads, choose_batch_size(context_tokens));
    return make_string(env, line);
}

namespace {
std::string jstring_or_empty(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(value, raw);
    return out;
}

bool deliver_pcm(JNIEnv * env, jobject sink, jmethodID method, const int16_t * samples, size_t count) {
    if (!samples || count == 0) return true;
    const jsize bytes = static_cast<jsize>(count * sizeof(int16_t));
    jbyteArray chunk = env->NewByteArray(bytes);
    if (!chunk) return false;
    env->SetByteArrayRegion(chunk, 0, bytes, reinterpret_cast<const jbyte *>(samples));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(chunk);
        return false;
    }
    env->CallVoidMethod(sink, method, chunk);
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

std::vector<int16_t> float_pcm_to_i16(const float * audio, size_t n_samples) {
    std::vector<int16_t> pcm(n_samples);
    for (size_t i = 0; i < n_samples; ++i) {
        float sample = audio[i];
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        pcm[i] = static_cast<int16_t>(sample * 32767.0f);
    }
    return pcm;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_NativeBridge_generateSpeechUtf8(
        JNIEnv * env, jclass, jlong handle, jbyteArray prompt, jstring language, jstring voice,
        jint max_frames, jint context_tokens, jint threads, jobject sink) {
    if (!handle) return make_string(env, "Model has not been loaded");
    if (!prompt || !sink) return make_string(env, "Missing prompt or audio callback");
    if (context_tokens < 512 || context_tokens > 8192 || context_tokens % 256 != 0 ||
        threads < 1 || threads > 8 || max_frames < 1 || max_frames > 4096)
        return make_string(env, "Invalid speech generation limits");
    auto state = find_model(handle);
    if (!state) return make_string(env, "Model handle is retired or invalid");
    std::unique_lock<std::mutex> decode_lock(state->decode_mutex, std::try_to_lock);
    if (!decode_lock.owns_lock()) return make_string(env, "Model is already generating");
    if (state->cancelled.load()) return nullptr;
    if (!state->multimodal) return make_string(env, "Speech generation requires a multimodal projector");
    const mtmd_gen_audio_info audio_info = mtmd_gen_audio_get_info(state->multimodal);
    if (audio_info.type == MTMD_GEN_AUDIO_TYPE_NONE)
        return make_string(env, "Speech generation is not available on this model");

    // Speech uses an ephemeral context; drop any text KV so it is not left inconsistent.
    state->release_session();

    const jsize input_size = env->GetArrayLength(prompt);
    std::string input(static_cast<size_t>(input_size), '\0');
    if (input_size > 0) env->GetByteArrayRegion(prompt, 0, input_size,
            reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return nullptr;

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_format = sink_class ? env->GetMethodID(sink_class, "onAudioFormat", "(III)V") : nullptr;
    jmethodID on_pcm = sink_class ? env->GetMethodID(sink_class, "onAudioPcm", "([B)V") : nullptr;
    jmethodID on_complete = sink_class ? env->GetMethodID(sink_class, "onAudioComplete", "()V") : nullptr;
    if (sink_class) env->DeleteLocalRef(sink_class);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!on_format || !on_pcm || !on_complete)
        return make_string(env, "Cannot locate Java audio callback");

    llama_context_params ctx_params = make_ctx_params(state.get(), context_tokens, threads);
    ctx_params.embeddings = true;
    llama_context * ctx = llama_init_from_model(state->model, ctx_params);
    if (!ctx) return make_string(env, "Unable to allocate speech context; insufficient RAM?");

    mtmd_helper_gen_audio * gen = mtmd_helper_gen_audio_init(ctx, state->multimodal);
    if (!gen) {
        llama_free(ctx);
        return make_string(env, "Speech generation is not available on this model");
    }

    std::string lang = jstring_or_empty(env, language);
    std::string spoken_voice = jstring_or_empty(env, voice);
    mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
    inp.seq_id = 0;
    inp.prompt = input.c_str();
    inp.prompt_len = input.size();
    inp.lang = lang.empty() ? nullptr : lang.c_str();
    inp.voice = spoken_voice.empty() ? nullptr : spoken_voice.c_str();
    inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    inp.stream = true;

    std::string error;
    if (mtmd_helper_gen_audio_set_input(gen, &inp) != 0) {
        error = "Could not prepare the speech generation prompt";
    }

    while (error.empty() && !state->cancelled.load()) {
        const int32_t remaining = mtmd_helper_gen_audio_step_prompt(gen, choose_batch_size(context_tokens));
        if (remaining < 0) {
            error = "Model failed while evaluating the speech prompt";
            break;
        }
        if (remaining == 0) break;
    }

    llama_sampler * sampler = nullptr;
    if (error.empty()) {
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    bool format_sent = false;
    int frames = 0;
    size_t total_samples = 0;
    if (error.empty() && !state->cancelled.load()) {
        llama_token sampled = LLAMA_TOKEN_NULL;
        if (mtmd_helper_gen_audio_next_is_text(gen)) {
            sampled = llama_sampler_sample(sampler, ctx, -1);
            llama_sampler_accept(sampler, sampled);
        }
        const float * h_state = llama_get_embeddings_ith(ctx, -1);
        bool stop = false;
        while (error.empty() && !state->cancelled.load() && !stop && frames < max_frames) {
            const float * h_next = nullptr;
            if (mtmd_helper_gen_audio_step_gen(gen, sampled, h_state, &h_next, &stop) != 0) {
                error = "Model failed while generating speech audio";
                break;
            }
            if (!h_next) break;
            ++frames;
            h_state = h_next;

            int32_t sample_rate = 0;
            const char * data = nullptr;
            size_t data_len = 0;
            int64_t n_samples = 0;
            if (mtmd_helper_gen_audio_get_output(gen, &sample_rate, &data, &data_len, &n_samples) != 0) {
                error = "Speech audio decode failed";
                break;
            }
            if (data_len > 0 && data) {
                if (!format_sent) {
                    const int rate = sample_rate > 0 ? sample_rate
                            : (audio_info.sample_rate > 0 ? audio_info.sample_rate : 24000);
                    env->CallVoidMethod(sink, on_format, rate, 1, 16);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        error = "Java audio format callback failed";
                        break;
                    }
                    format_sent = true;
                }
                if (n_samples > 0 && data_len == static_cast<size_t>(n_samples) * sizeof(float)) {
                    auto pcm = float_pcm_to_i16(reinterpret_cast<const float *>(data),
                            static_cast<size_t>(n_samples));
                    if (!deliver_pcm(env, sink, on_pcm, pcm.data(), pcm.size())) {
                        error = "Java audio callback failed";
                        break;
                    }
                    total_samples += pcm.size();
                } else if (data_len > 0 && (data_len % 2) == 0) {
                    if (!deliver_pcm(env, sink, on_pcm,
                            reinterpret_cast<const int16_t *>(data), data_len / 2)) {
                        error = "Java audio callback failed";
                        break;
                    }
                    total_samples += data_len / 2;
                }
            }

            if (mtmd_helper_gen_audio_next_is_text(gen)) {
                sampled = llama_sampler_sample(sampler, ctx, -1);
                llama_sampler_accept(sampler, sampled);
            } else {
                sampled = LLAMA_TOKEN_NULL;
            }
        }
    }

    if (error.empty() && !state->cancelled.load()) {
        if (mtmd_helper_gen_audio_flush(gen) == 0) {
            int32_t sample_rate = 0;
            const char * data = nullptr;
            size_t data_len = 0;
            int64_t n_samples = 0;
            if (mtmd_helper_gen_audio_get_output(gen, &sample_rate, &data, &data_len, &n_samples) == 0
                    && data_len > 0 && data) {
                if (!format_sent) {
                    const int rate = sample_rate > 0 ? sample_rate
                            : (audio_info.sample_rate > 0 ? audio_info.sample_rate : 24000);
                    env->CallVoidMethod(sink, on_format, rate, 1, 16);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        error = "Java audio format callback failed";
                    } else {
                        format_sent = true;
                    }
                }
                if (error.empty()) {
                    if (n_samples > 0 && data_len == static_cast<size_t>(n_samples) * sizeof(float)) {
                        auto pcm = float_pcm_to_i16(reinterpret_cast<const float *>(data),
                                static_cast<size_t>(n_samples));
                        if (!deliver_pcm(env, sink, on_pcm, pcm.data(), pcm.size()))
                            error = "Java audio callback failed";
                        else total_samples += pcm.size();
                    } else if ((data_len % 2) == 0) {
                        if (!deliver_pcm(env, sink, on_pcm,
                                reinterpret_cast<const int16_t *>(data), data_len / 2))
                            error = "Java audio callback failed";
                        else total_samples += data_len / 2;
                    }
                }
            }
        }
        if (error.empty() && total_samples == 0 && !state->cancelled.load())
            error = "Speech generation produced no audio";
        if (error.empty() && format_sent && !state->cancelled.load()) {
            env->CallVoidMethod(sink, on_complete);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                error = "Java audio completion callback failed";
            }
        }
    }

    if (sampler) llama_sampler_free(sampler);
    mtmd_helper_gen_audio_free(gen);
    llama_free(ctx);
    return error.empty() || state->cancelled.load() ? nullptr : make_string(env, error);
}
