// WakeEngine — fast offline wake-word + speech-to-text engine.
// A clean-room reference inspired by the architecture of the "Ordi" app's
// native engine, rebuilt with concrete speed improvements (see README).
//
// v0.2 pipeline (event-driven):
//   mic PCM -> Silero VAD on every 32 ms frame (streaming, cheap)
//           -> on speech start: mark utterance (with pre-roll)
//           -> on end of speech (silence): decode ONLY that utterance once
//   No Whisper runs while nobody is speaking.
#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct WakeConfig {
    std::string model_path;          // whisper ggml model (e.g. tiny.en q8_0)
    std::string vad_model_path;      // silero ggml VAD model
    std::string wake_phrase = "hey ordinary";
    int   sample_rate    = 16000;
    int   n_threads      = 4;        // configurable (Ordi hardcoded 2)
    bool  use_gpu        = true;     // Ordi forced use_gpu=0
    float vad_threshold  = 0.5f;     // Silero probability that starts speech
    float vad_release    = 0.35f;    // below this counts as silence (hysteresis)
    int   min_silence_ms = 500;      // silence that ends an utterance
    int   min_speech_ms  = 250;      // shorter blips are ignored, never decoded
    int   preroll_ms     = 300;      // audio kept from just before speech start
    int   max_utt_ms     = 8000;     // longer speech is decoded in 8 s pieces
    // Wake verification: Whisper is nudged toward the wake phrase with a
    // sentence-style prompt, then a match only counts if every token of the
    // wake phrase was decoded with at least this probability. Forced guesses
    // ("Hey Audrey" -> "Hey Ordinary") score lower than real ones.
    float wake_min_conf  = 0.40f;
    std::string prompt;              // empty = "<Wake Phrase>, what's the weather?"
};

struct WakeResult {
    bool        decoded       = false; // true if an utterance was transcribed this call
    bool        speaking      = false; // VAD currently inside speech
    bool        wake_detected = false;
    float       wake_conf     = 0.0f;  // confidence of the best wake-phrase match (0 if none)
    std::string transcript;            // text of the utterance(s) decoded this call
    double      audio_ms      = 0.0;   // audio length decoded this call
    double      proc_ms       = 0.0;   // Whisper decode time this call
    double      vad_ms        = 0.0;   // VAD time this call
};

class WakeEngine {
public:
    WakeEngine();
    ~WakeEngine();
    bool init(const WakeConfig& cfg);
    // Feed int16 PCM of any length. Decodes happen only when an utterance ends.
    WakeResult feed(const int16_t* pcm, size_t n_samples);
    // Decode any utterance still in progress (e.g. end of a file or on stop).
    WakeResult flush();
    // Drop buffered audio and VAD state.
    void reset();

private:
    struct Impl;
    Impl* p_ = nullptr;
};
