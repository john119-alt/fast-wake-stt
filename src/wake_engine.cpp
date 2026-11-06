#include "wake_engine.h"
#include "ring_buffer.h"
#include "text_utils.h"
#include "whisper.h"

#include <chrono>
#include <cctype>
#include <cstring>
#include <algorithm>

using fw::WordP;

namespace {

// Rebuild words from Whisper's tokens, keeping the lowest token probability
// seen inside each word (" Ord" + "inary" -> "ordinary", min(p1, p2)).
std::vector<WordP> token_words(whisper_context* ctx){
    std::vector<WordP> out; std::string cur; float cur_p = 1.0f;
    const whisper_token eot = whisper_token_eot(ctx);
    for(int s = 0, ns = whisper_full_n_segments(ctx); s < ns; ++s){
        for(int t = 0, nt = whisper_full_n_tokens(ctx, s); t < nt; ++t){
            whisper_token_data d = whisper_full_get_token_data(ctx, s, t);
            if(d.id >= eot) continue;                 // special / timestamp tokens
            for(const char* c = whisper_full_get_token_text(ctx, s, t); *c; ++c){
                if(std::isalnum((unsigned char)*c)){
                    cur += (char)std::tolower((unsigned char)*c);
                    cur_p = std::min(cur_p, d.p);
                } else if(!cur.empty()){
                    out.push_back({cur, cur_p}); cur.clear(); cur_p = 1.0f;
                }
            }
        }
    }
    if(!cur.empty()) out.push_back({cur, cur_p});
    return out;
}

double now_ms(){ using namespace std::chrono;
    return std::chrono::duration<double,std::milli>(steady_clock::now().time_since_epoch()).count(); }

// whisper.cpp logs every VAD call at INFO level; keep only warnings and errors.
void quiet_log(enum ggml_log_level level, const char* text, void*){
    if(level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}

constexpr size_t FRAME = 512;   // Silero window: 32 ms at 16 kHz
constexpr int    LEAD_PAD_MS = 300; // silence placed before each utterance; tiny.en
                                    // misreads speech that starts at sample 0

} // namespace

struct WakeEngine::Impl {
    WakeConfig cfg;
    whisper_context*     ctx = nullptr;
    whisper_vad_context* vad = nullptr;

    RingBuffer<float>  ring{1};   // recent audio; sized at init, never reallocated
    std::vector<float> pending;   // samples not yet run through VAD (< 1 frame after each feed)
    std::vector<float> scratch;   // int16 -> float conversion
    std::vector<float> utt;       // utterance copied out for decoding
    std::string        prompt;

    // utterance state, all in samples
    bool   in_speech = false;
    size_t utt_len = 0, speech_len = 0, silence_len = 0;

    size_t ms(int v) const { return (size_t)cfg.sample_rate * (size_t)v / 1000; }

    void clear_state(){ in_speech = false; utt_len = speech_len = silence_len = 0; }

    whisper_full_params params(size_t n_samples) const {
        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.n_threads        = cfg.n_threads;
        wp.print_progress   = false;
        wp.print_realtime   = false;
        wp.print_timestamps = false;
        wp.no_context       = true;
        wp.language         = "en";
        wp.vad              = false;          // already gated by our streaming VAD
        wp.initial_prompt   = prompt.c_str();
        // No temperature fallback + a token cap: a bad clip can't send Whisper
        // into a slow repetition loop ("hey hey hey hey" took 5 s in testing).
        wp.temperature_inc  = 0.0f;
        wp.max_tokens       = (int)(n_samples / (double)cfg.sample_rate * 5.0) + 6;
        // Drop "[Music]", "(applause)" and similar non-speech labels.
        wp.suppress_nst     = true;
        if(cfg.fit_audio_ctx){
            // 1 encoder position = 20 ms = 320 samples. Add 1 s of margin and
            // round up to a multiple of 64.
            int need = (int)(n_samples / 320) + 50;
            need = std::max(need, cfg.audio_ctx_min);
            need = ((need + 63) / 64) * 64;
            if(need < 1500) wp.audio_ctx = need;
        }
        return wp;
    }

    // Decode `len` samples that end `skip_end` samples before the newest one.
    void decode(size_t len, size_t skip_end, WakeResult& r){
        ring.tail(len, skip_end, utt);
        if(utt.empty()) return;
        const size_t speech = utt.size();
        utt.insert(utt.begin(), ms(LEAD_PAD_MS), 0.0f);

        whisper_full_params wp = params(utt.size());
        double t0 = now_ms();
        int rc = whisper_full(ctx, wp, utt.data(), (int)utt.size());
        r.proc_ms  += now_ms() - t0;
        r.audio_ms += 1000.0 * speech / cfg.sample_rate;
        r.decoded   = true;
        if(rc != 0) return;

        std::string text;
        for(int i = 0, n = whisper_full_n_segments(ctx); i < n; ++i)
            text += whisper_full_get_segment_text(ctx, i);
        text = fw::collapse_repeats(text);
        if(!r.transcript.empty() && !text.empty()) r.transcript += " ";
        r.transcript += text;

        float conf = fw::wake_match(token_words(ctx), cfg.wake_phrase);
        if(conf > r.wake_conf) r.wake_conf = conf;
        if(conf >= cfg.wake_min_conf){
            r.wake_detected = true;
            r.command = fw::after_wake(text, cfg.wake_phrase);
        }
    }

    // Advance the state machine by one VAD frame. `skip_end` = samples already
    // in the ring that come after this frame.
    void step(float p, size_t skip_end, WakeResult& r){
        if(!in_speech){
            if(p >= cfg.vad_threshold){
                in_speech   = true;
                utt_len     = ms(cfg.preroll_ms) + FRAME;
                speech_len  = FRAME;
                silence_len = 0;
            }
            return;
        }
        utt_len += FRAME;
        if(p >= cfg.vad_release){ speech_len += FRAME; silence_len = 0; }
        else                     { silence_len += FRAME; }

        if(silence_len >= ms(cfg.min_silence_ms)){
            // End of utterance. Keep ~150 ms of the trailing silence.
            size_t trim = silence_len > ms(150) ? silence_len - ms(150) : 0;
            if(speech_len >= ms(cfg.min_speech_ms))
                decode(utt_len - trim, skip_end + trim, r);
            clear_state();
            whisper_vad_reset_state(vad);
        } else if(utt_len >= ms(cfg.max_utt_ms)){
            // Long continuous speech: decode this piece and keep listening.
            decode(utt_len, skip_end, r);
            utt_len = speech_len = 0;
        }
    }
};

WakeEngine::WakeEngine() : p_(new Impl()) {}
WakeEngine::~WakeEngine(){
    if(!p_) return;
    if(p_->vad) whisper_vad_free(p_->vad);
    if(p_->ctx) whisper_free(p_->ctx);
    delete p_;
}

bool WakeEngine::init(const WakeConfig& cfg){
    whisper_log_set(quiet_log, nullptr);
    p_->cfg = cfg;

    // Room for the longest utterance + pre-roll + up to 4 s of not-yet-processed feed.
    size_t cap = p_->ms(cfg.max_utt_ms + cfg.preroll_ms + 4000);
    p_->ring = RingBuffer<float>(cap);
    p_->utt.reserve(cap + p_->ms(LEAD_PAD_MS));
    p_->pending.reserve(p_->ms(4000) + FRAME);
    p_->scratch.reserve(p_->ms(4000));

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = cfg.use_gpu;
    p_->ctx = whisper_init_from_file_with_params(cfg.model_path.c_str(), cparams);

    whisper_vad_context_params vparams = whisper_vad_default_context_params();
    vparams.n_threads = 1;      // Silero is tiny; one thread is plenty
    vparams.use_gpu   = false;
    p_->vad = whisper_vad_init_from_file_with_params(cfg.vad_model_path.c_str(), vparams);
    if(!p_->ctx || !p_->vad) return false;

    // Warm-up: the first whisper_full() call pays one-time setup (~3-4 s on a
    // laptop CPU). Do it here, on 1 s of silence, so the user's first
    // utterance gets normal speed.
    p_->prompt = cfg.prompt.empty() ? fw::default_prompt(cfg.wake_phrase) : cfg.prompt;
    std::vector<float> silence(p_->ms(1000), 0.0f);
    whisper_full_params wp = p_->params(silence.size());
    whisper_full(p_->ctx, wp, silence.data(), (int)silence.size());
    return true;
}

void WakeEngine::reset(){
    if(!p_) return;
    p_->ring.clear();
    p_->pending.clear();
    p_->clear_state();
    if(p_->vad) whisper_vad_reset_state(p_->vad);
}

WakeResult WakeEngine::feed(const int16_t* pcm, size_t n){
    WakeResult r;
    if(!p_ || !p_->ctx || !p_->vad || !pcm || n == 0) return r;
    Impl& s = *p_;

    s.scratch.resize(n);
    for(size_t i = 0; i < n; ++i) s.scratch[i] = (float)pcm[i] / 32768.0f;
    s.ring.push(s.scratch.data(), n);
    s.pending.insert(s.pending.end(), s.scratch.begin(), s.scratch.end());

    const size_t n_frames = s.pending.size() / FRAME;
    if(n_frames > 0){
        double t0 = now_ms();
        bool ok = whisper_vad_detect_speech_no_reset(s.vad, s.pending.data(), (int)(n_frames * FRAME));
        r.vad_ms = now_ms() - t0;
        if(ok){
            const float* probs = whisper_vad_probs(s.vad);
            const int    np    = whisper_vad_n_probs(s.vad);
            const size_t leftover = s.pending.size() - n_frames * FRAME;
            for(size_t f = 0; f < n_frames && (int)f < np; ++f){
                size_t skip_end = (n_frames - f - 1) * FRAME + leftover;
                s.step(probs[f], skip_end, r);
            }
        }
        s.pending.erase(s.pending.begin(), s.pending.begin() + n_frames * FRAME);
    }
    r.speaking = s.in_speech;
    return r;
}

WakeResult WakeEngine::flush(){
    WakeResult r;
    if(!p_ || !p_->ctx) return r;
    Impl& s = *p_;
    if(s.in_speech && s.speech_len >= s.ms(s.cfg.min_speech_ms))
        s.decode(s.utt_len + s.pending.size(), 0, r);
    s.clear_state();
    s.pending.clear();
    if(s.vad) whisper_vad_reset_state(s.vad);
    return r;
}
