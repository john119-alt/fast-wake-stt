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
    // Measured: real wakes 0.63-0.71 (incl. a real voice), near-miss
    // "Hey Audrey" 0.26-0.34. 0.45 sits between them.
    float wake_min_conf  = 0.45f;
    std::string prompt;              // empty = "<Wake Phrase>, what's the weather?"
    // Shrink Whisper's encoder window to the utterance length instead of the
    // fixed 30 s. Encoder cost scales with the window, so short phrases get
    // much faster. 0 disables (always 30 s).
    bool  fit_audio_ctx  = true;
    // Floor for the shrunken window, in encoder positions (20 ms each).
    // Measured on tiny.en: 256 causes repetition loops, 384+ does not; 512
    // keeps a margin (~86 ms per phrase vs ~250 ms at the full 1500).
    int   audio_ctx_min  = 512;
};

struct WakeResult {
    bool        decoded       = false; // true if an utterance was transcribed this call
    bool        speaking      = false; // VAD currently inside speech
    bool        wake_detected = false;
    float       wake_conf     = 0.0f;  // confidence of the best wake-phrase match (0 if none)
    std::string command;               // words after the wake phrase ("what's the weather?"),
                                       // empty if the user only said the wake phrase
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
