#include "wake_engine.h"
#include "ring_buffer.h"
#include "whisper.h"

#include <chrono>
#include <cctype>
#include <cstring>
#include <algorithm>

namespace {
std::string lower(std::string s){ for(char&c:s)c=(char)std::tolower((unsigned char)c); return s; }

// Lowercase, turn punctuation into spaces, split into words.
std::vector<std::string> words(const std::string& s){
    std::vector<std::string> out; std::string cur;
    for(char c: s){
        if(std::isalnum((unsigned char)c)) cur += (char)std::tolower((unsigned char)c);
        else if(!cur.empty()){ out.push_back(cur); cur.clear(); }
    }
    if(!cur.empty()) out.push_back(cur);
    return out;
}

int edit_distance(const std::string& a, const std::string& b){
    std::vector<int> prev(b.size()+1), cur(b.size()+1);
    for(size_t j=0;j<=b.size();++j) prev[j]=(int)j;
    for(size_t i=1;i<=a.size();++i){
        cur[0]=(int)i;
        for(size_t j=1;j<=b.size();++j)
            cur[j]=std::min({prev[j]+1, cur[j-1]+1, prev[j-1]+(a[i-1]!=b[j-1])});
        std::swap(prev,cur);
    }
    return prev[b.size()];
}

// Fuzzy wake match: the phrase's words must appear consecutively, each word
// within an edit-distance budget (1 for short words, 2 for longer ones).
bool fuzzy_contains(const std::string& text, const std::string& phrase){
    auto t = words(text), p = words(phrase);
    if(p.empty() || t.size() < p.size()) return false;
    for(size_t i=0;i+p.size()<=t.size();++i){
        bool ok = true;
        for(size_t k=0;k<p.size() && ok;++k){
            int budget = p[k].size() <= 4 ? 1 : 2;
            ok = edit_distance(t[i+k], p[k]) <= budget;
        }
        if(ok) return true;
    }
    return false;
}
double now_ms(){ using namespace std::chrono;
    return duration<double,std::milli>(steady_clock::now().time_since_epoch()).count(); }
}

struct WakeEngine::Impl {
    WakeConfig cfg;
    whisper_context* ctx = nullptr;
    RingBuffer<float> ring{16000};      // replaced at init with window size
    std::vector<float> scratch;         // reused; no per-feed realloc
    std::string prompt;                 // keeps initial_prompt alive during whisper_full
    Impl() {}
};

WakeEngine::WakeEngine() : p_(new Impl()) {}
WakeEngine::~WakeEngine(){ if(p_){ if(p_->ctx) whisper_free(p_->ctx); delete p_; } }

bool WakeEngine::init(const WakeConfig& cfg){
    p_->cfg = cfg;
    size_t cap = (size_t)cfg.sample_rate * cfg.window_ms / 1000;
    p_->ring = RingBuffer<float>(cap);      // IMPROVEMENT #2: allocate once
    p_->scratch.reserve(cap);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = cfg.use_gpu;          // IMPROVEMENT #1: GPU when built with a GPU backend
    p_->ctx = whisper_init_from_file_with_params(cfg.model_path.c_str(), cparams);
    return p_->ctx != nullptr;
}

void WakeEngine::reset(){ if(p_) p_->ring.clear(); }

WakeResult WakeEngine::feed(const int16_t* pcm, size_t n){
    WakeResult r;
    if(!p_ || !p_->ctx || !pcm || n==0) return r;

    // int16 -> float into a reused scratch buffer (no malloc on the hot path)
    p_->scratch.resize(n);
    for(size_t i=0;i<n;++i) p_->scratch[i] = (float)pcm[i] / 32768.0f;
    p_->ring.push(p_->scratch.data(), n);

    // Transcribe the current window with VAD gating (only speech is decoded).
    std::vector<float> audio; p_->ring.snapshot(audio);
    r.audio_ms = 1000.0 * audio.size() / p_->cfg.sample_rate;

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads        = p_->cfg.n_threads;     // IMPROVEMENT #3
    wp.print_progress   = false;
    wp.print_realtime   = false;
    wp.print_timestamps = false;
    wp.single_segment   = true;
    wp.language         = "en";
    wp.vad              = true;                  // IMPROVEMENT: VAD-gated decode
    wp.vad_model_path   = p_->cfg.vad_model_path.c_str();
    wp.vad_params       = whisper_vad_default_params();
    wp.vad_params.threshold = p_->cfg.vad_threshold;
    // Bias the decoder toward the wake phrase so it isn't misheard.
    p_->prompt = p_->cfg.wake_phrase;
    wp.initial_prompt   = p_->prompt.c_str();

    double t0 = now_ms();
    int rc = whisper_full(p_->ctx, wp, audio.data(), (int)audio.size());
    r.proc_ms = now_ms() - t0;
    r.rtf = r.audio_ms > 0 ? r.proc_ms / r.audio_ms : 0.0;
    if(rc != 0) return r;

    std::string text;
    int ns = whisper_full_n_segments(p_->ctx);
    for(int i=0;i<ns;++i) text += whisper_full_get_segment_text(p_->ctx, i);
    r.transcript = text;

    // Wake-phrase match on the transcript (as Ordi does on nativeText()).
    r.wake_detected = fuzzy_contains(text, p_->cfg.wake_phrase);
    return r;
}
