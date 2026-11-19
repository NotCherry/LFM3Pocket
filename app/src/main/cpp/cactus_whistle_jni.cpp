#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#if defined(HANAMI_CACTUS_WHISTLE)
#include "cactus_whistle/cactus.h"
#endif

namespace {

std::mutex g_mutex;

std::string last_error_string() {
#if defined(HANAMI_CACTUS_WHISTLE)
    const char * err = cactus_last_error();
    return err ? std::string(err) : std::string();
#else
    return "Cactus runtime is not available in this build";
#endif
}

jstring make_jstring(JNIEnv * env, const std::string & value) {
    if (!env) return nullptr;
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeProbe(JNIEnv *, jclass) {
#if defined(HANAMI_CACTUS_WHISTLE)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeLoad(JNIEnv * env, jclass, jbyteArray modelBytes) {
#if !defined(HANAMI_CACTUS_WHISTLE)
    (void) env;
    (void) modelBytes;
    return -1;
#else
    if (!env || !modelBytes) return -1;
    jsize len = env->GetArrayLength(modelBytes);
    if (len <= 0) return -1;
    std::vector<unsigned char> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(modelBytes, 0, len, reinterpret_cast<jbyte *>(buffer.data()));
    std::lock_guard<std::mutex> lock(g_mutex);
    return cactus_load(buffer.data(), static_cast<unsigned long long>(buffer.size()));
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeInit(JNIEnv * env, jclass, jstring systemPrompt,
                                                           jstring toolsJson, jstring toolIndexPath) {
#if !defined(HANAMI_CACTUS_WHISTLE)
    (void) env;
    (void) systemPrompt;
    (void) toolsJson;
    (void) toolIndexPath;
    return -1;
#else
    const char * system = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : "";
    const char * tools = toolsJson ? env->GetStringUTFChars(toolsJson, nullptr) : "";
    const char * index = toolIndexPath ? env->GetStringUTFChars(toolIndexPath, nullptr) : nullptr;
    std::lock_guard<std::mutex> lock(g_mutex);
    int rc = cactus_init(system ? system : "", tools ? tools : "", index);
    if (systemPrompt) env->ReleaseStringUTFChars(systemPrompt, system);
    if (toolsJson) env->ReleaseStringUTFChars(toolsJson, tools);
    if (toolIndexPath) env->ReleaseStringUTFChars(toolIndexPath, index);
    return rc;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeLastError(JNIEnv * env, jclass) {
    return make_jstring(env, last_error_string());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeTranscribe(JNIEnv * env, jclass, jfloatArray pcmArray,
                                                                  jint samples, jstring language,
                                                                  jstring keywords, jboolean wordTimestamps) {
#if !defined(HANAMI_CACTUS_WHISTLE)
    (void) env;
    (void) pcmArray;
    (void) samples;
    (void) language;
    (void) keywords;
    (void) wordTimestamps;
    return nullptr;
#else
    if (!env || !pcmArray || samples <= 0) return nullptr;
    jsize len = env->GetArrayLength(pcmArray);
    if (len < samples) samples = len;
    std::vector<float> pcm(static_cast<size_t>(samples));
    env->GetFloatArrayRegion(pcmArray, 0, samples, pcm.data());

    const char * lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char * keys = keywords ? env->GetStringUTFChars(keywords, nullptr) : nullptr;

    // Output is typically JSON text; allocate generously (similar to upstream Python wrapper).
    int capacity = std::max(4096, static_cast<int>(samples / 16000) * 4096);
    if (capacity < 8192) capacity = 8192;
    std::vector<char> out(static_cast<size_t>(capacity));

    std::string result;
    int rc = -1;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        rc = cactus_transcribe(pcm.data(), samples, lang, keys, wordTimestamps ? 1 : 0, out.data(), capacity);
        if (rc >= 0) {
            out.back() = '\0';
            result.assign(out.data());
        }
    }

    if (language) env->ReleaseStringUTFChars(language, lang);
    if (keywords) env->ReleaseStringUTFChars(keywords, keys);

    if (rc < 0) return nullptr;
    return make_jstring(env, result);
#endif
}
