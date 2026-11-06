// JNI bridge between the Kotlin app and WakeEngine.
#include <jni.h>
#include <string>
#include <android/log.h>
#include "wake_engine.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FastWake", __VA_ARGS__)

static std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fastwake_app_NativeEngine_create(JNIEnv* env, jobject,
        jstring model, jstring vad, jint threads, jstring phrase) {
    WakeConfig cfg;
    cfg.model_path     = str(env, model);
    cfg.vad_model_path = str(env, vad);
    cfg.wake_phrase    = str(env, phrase);
    cfg.n_threads      = threads;
    cfg.use_gpu        = false;   // CPU build for v0.1; GPU backend is the next step
    auto* eng = new WakeEngine();
    if (!eng->init(cfg)) { delete eng; LOG("engine init failed"); return 0; }
    LOG("engine ready, threads=%d", threads);
    return reinterpret_cast<jlong>(eng);
}

// Returns "wake<TAB>decode_ms<TAB>transcript"
extern "C" JNIEXPORT jstring JNICALL
Java_com_fastwake_app_NativeEngine_feed(JNIEnv* env, jobject,
        jlong handle, jshortArray pcm, jint n) {
    auto* eng = reinterpret_cast<WakeEngine*>(handle);
    if (!eng) return env->NewStringUTF("0\t0\t");
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    WakeResult r = eng->feed(reinterpret_cast<const int16_t*>(data), (size_t)n);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    std::string out = std::string(r.wake_detected ? "1" : "0") + "\t" +
                      std::to_string((int)r.proc_ms) + "\t" + r.transcript;
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fastwake_app_NativeEngine_reset(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<WakeEngine*>(handle)->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_fastwake_app_NativeEngine_free(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<WakeEngine*>(handle);
}
