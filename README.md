# FastWake

A hands-free voice assistant for Android. Say **"Hey Ordinary, what's the
weather in Mumbai?"** and it answers out loud. Wake-word detection and
speech-to-text run **on the phone** (whisper.cpp + Silero VAD); answers come
from **Claude** with live **web search**, streamed and spoken sentence by
sentence.

This is a clean-room project. Its on-device engine was designed after studying
how the "Ordi" app works, then rebuilt with measurable speed improvements. None
of Ordi's code, models, or assets are used. ("Hey Ordinary" is just the current
wake phrase and can be changed.)

## How it works

```
mic (16 kHz) ─▶ Silero VAD on every 32 ms ─▶ end of speech ─▶ Whisper (on device)
                                                                    │
            "Hey Ordinary, <question>"  ◀── wake-phrase check + confidence
                       │
                       ▼
         Claude (streaming, web search) ─▶ sentence splitter ─▶ text-to-speech
                       │
                       └─ follow-up window: reply without the wake word for 7 s
```

- **Listening** runs as a foreground service with a "FastWake is listening"
  notification, so it works with the screen off.
- **Only your question** (the words after the wake phrase) leaves the phone, and
  it goes only to Anthropic. Everything else you say is transcribed on the phone
  and discarded.
- **Speaking starts after the first sentence**, while the rest of the answer is
  still streaming.
- **Follow-ups**: after an answer, just keep talking. Web-search results stay in
  the conversation, so "and tomorrow?" works.
- **History** is saved on the phone only.

## Install and set up (Android)

1. On your phone, open **github.com/john119-alt/fast-wake-stt → Releases**,
   download the newest `FastWake-v*.apk`, and install it. Requires an arm64
   phone with Android 9 or newer. New versions install over old ones.
2. Get a Claude API key at **console.anthropic.com → API Keys** (pay-as-you-go,
   billed to your Anthropic account).
3. In the app, open **Settings**, paste the key, tap **Save key**, then
   **Test key**.
4. Back on the main screen tap **Start listening** and allow the microphone. The
   first start downloads the speech models (~44 MB, use Wi-Fi).
5. Say *"Hey Ordinary, …"*.

### Settings
| Setting | Default | |
|---|---|---|
| Model | Opus 5.5 | Opus 5.5 (smartest), Sonnet 5.5 (balanced), Haiku 5.5 (fastest, cheapest) |
| Follow-up questions | on | listen 7 s after an answer without the wake word |
| Wake chime | on | tone when it hears the wake phrase on its own |
| Speaking speed | 1.0× | 0.7×–1.5× |

The API key is encrypted with an Android Keystore key (hardware-backed on most
phones). It's sent only to Anthropic. A shared server is planned before giving
the app to other people (see Roadmap), since any key inside an app can be
extracted.

## Claude integration

`android/app/src/main/java/com/fastwake/app/assistant/ClaudeAssistant.kt`, using
the official Claude Java SDK (`com.anthropic:anthropic-java:3.6.0`):

- Streaming Messages API, `output_config.effort = low` for quick spoken answers,
  and a voice-specific system prompt with the user's local date, time and country.
- Web search: `web_search_20260209` (Opus/Sonnet) or `web_search_20250305`
  (Haiku), max 3 searches, with the phone's timezone and country.
- Conversation history keeps Claude's full replies (including search results).
  `pause_turn` is resumed automatically.
- Server-side refusal fallbacks (`fallbacks: "default"`, beta
  `server-side-fallback-2026-07-01`) on Opus 5.5 and Sonnet 5.5. A declined
  question is retried on another model instead of failing.
- Errors (bad key, no credit, rate limit, overloaded, offline) become short
  spoken messages.

## Speed (on-device engine)

Measured on a 4-core Intel Xeon 2.1 GHz, CPU only, tiny.en q8_0, 4 threads.

| | v0.1 | v0.2 | v0.3 |
|---|---|---|---|
| How Whisper runs | re-decodes 8 s window every second | once per finished phrase | same, with encoder window fitted to the phrase |
| Decode per phrase | ~250 ms | ~250 ms | **~60–100 ms** |
| 34 s session, total compute | 7,897 ms | 1,448 ms | **634 ms** (53× real-time) |

On a real phone (v0.2, Android tablet) a phrase took ~730 ms regardless of
length, because Whisper always processed a 30 s window. v0.3 fits the window to
the phrase (minimum 512 positions ≈ 10 s; smaller windows caused repetition
loops in testing).

### Compared with Ordi's engine (from its decompiled native library)
| Ordi | FastWake |
|---|---|
| `use_gpu = 0` (CPU only) | configurable; `-DWAKE_ENABLE_VULKAN=ON` for a GPU backend |
| reallocates a growing `std::vector<float>` on every audio feed | fixed ring buffer, allocated once |
| 2 threads, hard-coded | configurable (2 → 4 threads: −35% decode time) |
| waits for the full cloud reply before speaking | streams and speaks the first sentence first |

## Wake-word accuracy

A general speech model isn't a dedicated wake-word detector. Whisper is nudged
toward the wake phrase, and a wake only counts if every wake-phrase token was
decoded with probability ≥ 0.45. Real wakes scored 0.63–0.71 (including a real
voice on a phone). The near-miss "Hey Audrey" scored 0.26–0.34 and is rejected.
Decoder loops ("and so my fellow Americans and so my fellow…") are collapsed
before the text is used. A dedicated keyword-spotting model is on the roadmap.

## Tests

| Suite | What | Run |
|---|---|---|
| Engine unit tests | wake matching, loop collapsing, command extraction | `ctest --test-dir build` |
| Audio regression | 7 clips with expected wake/no-wake, fails on any loop | `scripts/test.sh` |
| App unit tests (23) | Claude client against a mock streaming server; sentence splitter; conversation state machine | `cd android && ./gradlew testReleaseUnitTest` |
| R8 smoke test | the shrunk release build still talks to Claude correctly (requests, streaming, search history, errors) | `cd android && ./r8-smoke.sh` |

GitHub Actions runs all of them on every push. An APK is only published if they
pass.

## Build

```bash
git clone --recursive https://github.com/john119-alt/fast-wake-stt.git

# desktop engine + CLI
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
mkdir -p models
curl -L -o models/ggml-tiny.en-q8_0.bin  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin
curl -L -o models/ggml-silero-v5.1.2.bin https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin
./build/wake_stt models/ggml-tiny.en-q8_0.bin models/ggml-silero-v5.1.2.bin test-audio/session.wav 4 0

# Android app (SDK + NDK 27.2.12479018)
cd android && echo "sdk.dir=$ANDROID_HOME" > local.properties && ./gradlew assembleRelease
```
Releases are R8-shrunk (6.4 MB instead of 45 MB, since the Claude SDK covers the
whole API) and signed with the committed debug key so they install directly. Use
a real signing key before publishing to a store.

## Layout
```
src/                    on-device engine (C++): wake_engine, ring_buffer, text_utils, CLI
tests/                  engine unit tests
scripts/test.sh         audio regression tests
test-audio/             wake / no-wake / near-miss / long-speech clips
android/app/src/main/
  cpp/jni.cpp           JNI bridge to the engine
  java/com/fastwake/app/
    assistant/          Claude client, sentence splitter
    service/            foreground listening service, conversation state machine
    speech/             text-to-speech, chimes
    data/               encrypted key storage, settings, history, model download
    ui/                 main, settings, history screens
android/r8-smoke.sh     shrunk-build verification
vendor/whisper.cpp      upstream whisper.cpp (submodule, pinned)
```

## Roadmap
- Server backend (key stays on a server) before sharing the app.
- Custom wake phrase, plus a dedicated keyword-spotting model.
- GPU (Vulkan) build for Android.
- Phone actions: alarms, timers, reminders, calls, messages.

## License
Your code in this repo; whisper.cpp under its MIT license
(`vendor/whisper.cpp/LICENSE`).
