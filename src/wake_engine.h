// WakeEngine — fast offline wake-word + speech-to-text engine.
// A clean-room reference inspired by the architecture of the "Ordi" app's
// native engine, rebuilt with four concrete improvements (see README).
#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct whisper_context;

struct WakeConfig {
    std::string model_path;          // whisper ggml model (e.g. tiny.en q8_0)
    std::string vad_model_path;      // silero ggml VAD model
    std::string wake_phrase = "hey ordinary";
    int   sample_rate   = 16000;
    int   n_threads     = 4;         // IMPROVEMENT #3: configurable (Ordi hardcoded 2)
    bool  use_gpu       = true;      // IMPROVEMENT #1: Ordi forced use_gpu=0
    float vad_threshold = 0.5f;      // Silero speech probability threshold
    int   window_ms     = 8000;      // sliding audio window kept in the ring buffer
};

struct WakeResult {
    bool        wake_detected = false;
    std::string transcript;
    double      audio_ms   = 0.0;    // audio processed this pass
    double      proc_ms    = 0.0;    // wall-clock to transcribe
    double      rtf        = 0.0;    // proc_ms / audio_ms  (lower = faster)
};

class WakeEngine {
public:
    WakeEngine();
    ~WakeEngine();
    bool init(const WakeConfig& cfg);
    // Feed int16 PCM (as from the mic / a WAV). Returns a result when a window
    // is transcribed; wake_detected is set if the wake phrase is present.
    WakeResult feed(const int16_t* pcm, size_t n_samples);
    void reset();

private:
    struct Impl;
    Impl* p_ = nullptr;
};
