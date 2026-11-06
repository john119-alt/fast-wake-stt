// JNI bridge between the Kotlin app and WakeEngine.
#include <jni.h>
#include <string>
#include <cstdio>
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

// Result encoding shared by feed() and flush():
// "decoded<TAB>speaking<TAB>wake<TAB>conf<TAB>decode_ms<TAB>audio_ms<TAB>transcript"
static jstring encode(JNIEnv* env, const WakeResult& r) {
    char head[96];
    snprintf(head, sizeof head, "%d\t%d\t%d\t%.2f\t%d\t%d\t",
             r.decoded, r.speaking, r.wake_detected, r.wake_conf,
             (int)r.proc_ms, (int)r.audio_ms);
    return env->NewStringUTF((std::string(head) + r.transcript).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fastwake_app_NativeEngine_feed(JNIEnv* env, jobject,
        jlong handle, jshortArray pcm, jint n) {
    auto* eng = reinterpret_cast<WakeEngine*>(handle);
    if (!eng) return encode(env, WakeResult{});
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    WakeResult r = eng->feed(reinterpret_cast<const int16_t*>(data), (size_t)n);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    return encode(env, r);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fastwake_app_NativeEngine_flush(JNIEnv* env, jobject, jlong handle) {
    auto* eng = reinterpret_cast<WakeEngine*>(handle);
    return encode(env, eng ? eng->flush() : WakeResult{});
}

extern "C" JNIEXPORT void JNICALL
Java_com_fastwake_app_NativeEngine_reset(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<WakeEngine*>(handle)->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_fastwake_app_NativeEngine_free(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<WakeEngine*>(handle);
}
