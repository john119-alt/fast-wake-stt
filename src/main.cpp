// wake_stt — CLI harness: load a 16 kHz mono WAV, stream it through WakeEngine
// in chunks (simulating the mic), print the transcript + a speed benchmark.
#include "wake_engine.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include <fstream>
#include <chrono>
#include <string>
#include <algorithm>

// Minimal WAV reader: 16-bit PCM mono. Returns samples at file's rate.
static bool read_wav(const char* path, std::vector<int16_t>& out, int& rate){
    std::ifstream f(path, std::ios::binary);
    if(!f) return false;
    char riff[4]; f.read(riff,4); if(strncmp(riff,"RIFF",4)) return false;
    f.ignore(4); char wave[4]; f.read(wave,4); if(strncmp(wave,"WAVE",4)) return false;
    uint16_t bits=0, ch=0; uint32_t sr=0; 
    while(f){
        char id[4]; uint32_t sz=0;
        f.read(id,4); f.read((char*)&sz,4); if(!f) break;
        if(!strncmp(id,"fmt ",4)){
            char fmt[16]; f.read(fmt,16>sz?sz:16);
            uint16_t af; memcpy(&af,fmt,2); memcpy(&ch,fmt+2,2);
            memcpy(&sr,fmt+4,4); memcpy(&bits,fmt+14,2);
            if(sz>16) f.ignore(sz-16);
        } else if(!strncmp(id,"data",4)){
            size_t ns = sz/2; out.resize(ns); f.read((char*)out.data(), sz); break;
        } else f.ignore(sz);
    }
    rate = (int)sr;
    return bits==16 && ch==1 && sr>0 && !out.empty();
}

int main(int argc, char** argv){
    if(argc < 4){
        fprintf(stderr,"usage: %s <whisper_model> <vad_model> <audio.wav> "
                       "[threads] [use_gpu 0|1] [wake_phrase]\n", argv[0]);
        return 2;
    }
    WakeConfig cfg;
    cfg.model_path     = argv[1];
    cfg.vad_model_path = argv[2];
    cfg.n_threads      = argc>4 ? atoi(argv[4]) : 4;
    cfg.use_gpu        = argc>5 ? atoi(argv[5])!=0 : true;
    if(argc>6) cfg.wake_phrase = argv[6];
    // A/B switches for benchmarking (environment variables).
    if(const char* e = getenv("FW_FULL_CTX")) cfg.fit_audio_ctx = atoi(e) == 0;
    if(const char* e = getenv("FW_CTX_MIN"))  cfg.audio_ctx_min = atoi(e);

    std::vector<int16_t> pcm; int rate=0;
    if(!read_wav(argv[3], pcm, rate)){ fprintf(stderr,"bad WAV (need 16-bit mono)\n"); return 1; }
    cfg.sample_rate = rate;

    WakeEngine eng;
    auto t_load = std::chrono::steady_clock::now();
    if(!eng.init(cfg)){ fprintf(stderr,"engine init failed\n"); return 1; }
    printf("engine load   : %.0f ms (model load + warm-up, once per app start)\n",
           std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t_load).count());

    // Stream in 1-second chunks, like a live mic feed.
    const size_t chunk = (size_t)rate;
    double decode_ms = 0.0, vad_ms = 0.0, decoded_audio_ms = 0.0;
    int decodes = 0; bool woke = false;
    std::string transcript;
    auto take = [&](const WakeResult& r, double at_ms){
        vad_ms += r.vad_ms;
        if(!r.decoded) return;
        ++decodes; decode_ms += r.proc_ms; decoded_audio_ms += r.audio_ms;
        woke = woke || r.wake_detected;
        char conf[32] = "";
        if(r.wake_conf > 0) snprintf(conf, sizeof conf, " conf=%.2f", r.wake_conf);
        printf("  [t=%5.1fs] decoded %.1fs of speech in %4.0f ms%s%s: %s\n",
               at_ms/1000.0, r.audio_ms/1000.0, r.proc_ms, conf,
               r.wake_detected ? " <WAKE>" : "", r.transcript.c_str());
        if(r.wake_detected) printf("             command after wake: \"%s\"\n", r.command.c_str());
        if(!transcript.empty()) transcript += " |";
        transcript += r.transcript;
    };
    for(size_t off=0; off<pcm.size(); off+=chunk){
        size_t n = std::min(chunk, pcm.size()-off);
        take(eng.feed(pcm.data()+off, n), 1000.0*(off+n)/rate);
    }
    take(eng.flush(), 1000.0*pcm.size()/rate);

    const double clip_ms = 1000.0 * pcm.size() / rate;
    const double total_ms = decode_ms + vad_ms;
    printf("\n================ RESULT ================\n");
    printf("transcript    : %s\n", transcript.c_str());
    printf("wake detected : %s\n", woke ? "YES" : "no");
    printf("threads=%d  gpu=%d\n", cfg.n_threads, (int)cfg.use_gpu);
    printf("clip length   : %.0f ms  (%d decodes, %.0f ms of speech decoded)\n",
           clip_ms, decodes, decoded_audio_ms);
    printf("VAD compute   : %.0f ms\n", vad_ms);
    printf("decode compute: %.0f ms\n", decode_ms);
    printf("total compute : %.0f ms\n", total_ms);
    const double rtf = clip_ms>0 ? total_ms/clip_ms : 0.0;
    printf("stream RTF    : %.3f  (%.1fx real-time)\n", rtf, rtf>0 ? 1.0/rtf : 0.0);
    printf("========================================\n");
    return 0;
}
