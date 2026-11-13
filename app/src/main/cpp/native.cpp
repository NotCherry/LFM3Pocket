#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
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
    std::atomic<bool> cancelled{false};
    std::mutex decode_mutex;
    ~ModelHandle() {
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
                 llama_pos start, bool mrope) {
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
        mm_params.n_threads = params.n_gpu_layers == 0 ? 4 : 2;
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

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.abort_callback = [](void * data) { return static_cast<std::atomic<bool> *>(data)->load(); };
    ctx_params.abort_callback_data = &state->cancelled;
    ctx_params.n_ctx = context_tokens;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    const unsigned cpu = std::thread::hardware_concurrency();
    ctx_params.n_threads = static_cast<int32_t>(std::min(static_cast<unsigned>(threads), std::max(1u, cpu)));
    ctx_params.n_threads_batch = ctx_params.n_threads;
    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) return make_string(env, "Unable to allocate context; insufficient RAM?");

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    if (!batch) {
        llama_free(ctx);
        return make_string(env, "Cannot allocate inference batch");
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID callback = sink_class ? env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (!callback && env->ExceptionCheck()) env->ExceptionClear();
    if (sink_class) env->DeleteLocalRef(sink_class);

    std::string error;
    std::string pending;
    if (!callback) {
        error = "Cannot locate Java token callback";
    } else {
        int position = 0;
        const jsize media_count = media_paths ? env->GetArrayLength(media_paths) : 0;
        const bool has_media = media_count > 0;
        if (has_media) {
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
                        if (mtmd_helper_eval_chunks(state->multimodal, ctx, chunks, 0, 0,
                                                    512, true, &next_position) != 0) {
                            error = "Model failed while evaluating the selected media";
                        } else {
                            position = static_cast<int>(next_position);
                            if (position + 1 >= ctx_params.n_ctx) {
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
        } else {
            while (position < converted && !state->cancelled.load()) {
                int n = std::min(512, converted - position);
                setup_batch(batch, tokens.data() + position, n, position, mrope);
                if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
                    error = "Model failed while evaluating the prompt";
                    break;
                }
                position += n;
            }
        }

        for (int i = 0; error.empty() && !state->cancelled.load() &&
                        i < max_tokens && position + i < context_tokens; ++i) {
            const llama_token next = llama_sampler_sample(sampler, ctx, -1);
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

            if (i + 1 == max_tokens || position + i + 1 >= context_tokens) break;
            setup_batch(batch, &next, 1, position + i, mrope);
            if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
                error = "Model failed while decoding response";
                break;
            }
        }
        if (!pending.empty() && error.empty()) deliver(env, sink, callback, u"\uFFFD");
    }

    llama_sampler_free(sampler);
    llama_batch_ext_free(batch);
    llama_free(ctx);
    return error.empty() || state->cancelled.load() ? nullptr : make_string(env, error);
}

