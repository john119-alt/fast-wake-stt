# fast-wake-stt

A clean-room **offline wake-word + speech-to-text engine** in C++ (whisper.cpp +
Silero VAD). It's a reference you own, inspired by the *architecture* of the
"Ordi" app's native engine but rebuilt with four concrete speed/quality
improvements. None of Ordi's code is used.

## What it does (v0.2)
1. Runs the Silero voice detector on every 32 ms of incoming audio (streaming,
   ~4 ms of compute per second of audio).
2. When speech starts, marks an utterance (with 300 ms of pre-roll so the first
   word isn't clipped).
3. After ~0.5 s of silence, transcribes **only that utterance**, once, with
   Whisper. Nothing is transcribed while nobody is talking.
4. Checks the transcript for the wake phrase ("hey ordinary" by default) and
   verifies Whisper's confidence in it, to reject forced guesses.

## The four improvements over Ordi's engine
| # | Ordi (from decompilation) | Here |
|---|---|---|
| 1 | `whisper_context_default_params` with **`use_gpu = 0`** (CPU only) | `use_gpu` is configurable; build with `-DWAKE_ENABLE_VULKAN=ON` for a GPU backend |
| 2 | `ordi_wake_feed` **reallocated a growing `std::vector<float>`** every call on the always-on path | `RingBuffer<float>` allocated **once**, no per-feed malloc |
| 3 | thread count **hardcoded to 2** | `n_threads` configurable (CLI arg) |
| 4 | reply waited on the full backend round-trip | streaming design — see "Streaming" below |
| 5 | (v0.2) | event-driven decoding: Whisper runs once per finished utterance, not continuously |

## Get the code
```bash
git clone --recursive https://github.com/john119-alt/fast-wake-stt.git
# already cloned without --recursive?
git submodule update --init
```

## Files
```
src/ring_buffer.h       fixed-capacity circular audio buffer (improvement #2)
src/wake_engine.h/.cpp  the engine (init / feed / flush / reset)
src/main.cpp            CLI: load WAV, stream in 1s chunks, print each utterance + timing
CMakeLists.txt          desktop build; Vulkan toggle (improvement #1)
android/                FastWake Android app (Kotlin + JNI, arm64)
scripts/test.sh         wake-word regression tests (expected answer per clip)
test-audio/             wake, no-wake, near-miss and long-speech test clips
.github/workflows/      APK build + release, engine tests (run on every push)
vendor/whisper.cpp      upstream whisper.cpp, git submodule pinned to 727d3e7
```

## Android app (FastWake)
Every push to `main` is built by GitHub Actions
(`.github/workflows/android-apk.yml`) and published on the **Releases** page as
`FastWake-v<version>.apk`. Bumping `versionName` in
`android/app/build.gradle.kts` starts a new release; otherwise the current
version's APK is replaced with the newest build. All builds share the committed
debug key, so a new APK installs as an update over the old one.

Install the APK on an arm64 phone running Android 9 or newer. On first launch it downloads the two official models
(~44 MB) into app storage. Press **Start listening**, then say "Hey Ordinary".
The badge turns purple while it hears speech and green on a wake. Each finished
phrase is logged as `[2.3s speech → 412 ms] conf=0.55 …`: speech length, decode
time on your phone, and wake-word confidence.

Build it yourself (Android SDK + NDK 27.2.12479018):
```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```
The release build is signed with the debug key so it installs directly; use a
real signing key before publishing anywhere.

## Build (desktop, CPU)
```bash
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

## Build (desktop, GPU — improvement #1)
Install the Vulkan SDK, then:
```bash
cmake -S . -B build -G Ninja -DWAKE_ENABLE_VULKAN=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

## Run / benchmark
```bash
# wake_stt <whisper_model> <vad_model> <audio.wav> [threads] [use_gpu] [wake_phrase]
./build/wake_stt ggml-tiny.en-q8_0.bin ggml-silero-v5.1.2.bin sample.wav 4 1 "hey ordinary"
```
Models (official, not committed to git):
```bash
mkdir -p models
curl -L -o models/ggml-tiny.en-q8_0.bin  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin
curl -L -o models/ggml-silero-v5.1.2.bin https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin
```
Audio
must be 16 kHz, 16-bit, mono WAV. The tool prints RTF (lower = faster); compare
`threads` and `use_gpu 0` vs `1` to quantify each lever.

## Measured results (4-core Intel Xeon 2.1 GHz, CPU only, tiny.en q8_0, 4 threads)

**v0.1 vs v0.2 on `session.wav`** (34 s: three phrases separated by silence,
which is what real listening looks like):

| | v0.1 (re-decode window every second) | v0.2 (decode finished utterances) |
|---|---|---|
| Total compute | 7,897 ms | **1,448 ms** (5.5× less) |
| Speed | 4.3× real-time | **23.3× real-time** |
| Phrases transcribed | only the last window | **all six** |
| Engine load (once per app start) | — | 289 ms incl. warm-up |
| Voice-detector cost | — | ~118 ms for 34 s of audio |

Each utterance decodes in ~200–250 ms on this CPU.
Threads (lever #3, v0.1 measurement on jfk.wav): 1 → 8,759 ms ·
**2 (Ordi) → 5,112 ms** · **4 → 3,347 ms** (−35%).
GPU (lever #1) not measured: this machine has no GPU.

## Wake-word detection and its limits
A general speech-to-text model is not a dedicated wake-word detector, so there's
a tradeoff (measured on synthetic espeak voices):

| Whisper hint | Real "Hey Ordinary" | Near-miss "Hey Audrey" |
|---|---|---|
| none | misheard ("Hey, what did I read?") | correct, no wake |
| bare "hey ordinary" | **stuck in a "hey hey hey hey" loop, 5 s** | — |
| sentence "Hey Ordinary, what's the weather?" | correct | **transcribed as "Hey Ordinary"** |

v0.2 uses the sentence hint, then **verifies**: a wake only counts if every
wake-phrase token was decoded with probability ≥ `wake_min_conf` (0.40).
Measured token confidence for "Ord": real wake 0.58 / 0.51, "Hey Audrey" 0.26,
so the near-miss is rejected. Temperature fallback is off and output is
token-capped, so a bad clip can't loop. **This threshold was tuned on synthetic
voices — tune it on real recordings** (the app logs `conf=` for each wake).
The robust long-term fix is a dedicated keyword-spotting model.

## Tests
```bash
scripts/test.sh            # build/ and models/ by default
```
Seven clips with an expected answer each: real wakes (`hey`, `wake_question`,
`session`) must trigger; `nowake`, `nearmiss_audrey`, `nearmiss_ordinary`, `jfk`
must not. GitHub Actions runs this on every push.

## Test it with your own voice
Record yourself saying "Hey Ordinary, …" on your phone, copy it over, then:
```bash
ffmpeg -i my_recording.m4a -ar 16000 -ac 1 -sample_fmt s16 me.wav
./build/wake_stt models/ggml-tiny.en-q8_0.bin models/ggml-silero-v5.1.2.bin me.wav 4 0
```

## Build for Android (what ships in the app)
Use the NDK toolchain; enable the GPU backend for ggml:
```bash
cmake -S . -B build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DWAKE_ENABLE_VULKAN=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-android -j
```
Wrap `WakeEngine` in a small JNI shim (mirroring Ordi's
`nativeCreate/feed/text`) and load the resulting `.so` from your app. From
Flutter, call it over a platform channel or `dart:ffi`.

## Streaming (improvement #4) — design
Ordi waits for the full Lambda→Gemini reply before speaking. To cut perceived
latency:
- **STT side:** call `whisper_full` on a sliding window and emit partial text as
  it grows (don't wait for end-of-utterance) — start sending to the backend
  early.
- **Backend side:** have your `/chat` endpoint **stream** tokens (SSE or chunked
  HTTP) from Gemini, and start TTS on the first sentence instead of the whole
  reply. Keep the model connection warm (provisioned concurrency) to kill cold
  starts.

## Tuning knobs (in `WakeConfig`)
`n_threads` (match performance cores), `vad_threshold` / `vad_release`
(Silero sensitivity + hysteresis), `min_silence_ms` (how long a pause ends an
utterance — lower = faster response, but long pauses split sentences),
`min_speech_ms`, `preroll_ms`, `max_utt_ms`, `wake_min_conf`, `prompt`, and
model/quantization (tiny.en q8_0 → q5_1/q4_0 for speed, base.en for accuracy).

## License
Your code under this repo; whisper.cpp under its MIT license (see
`vendor/whisper.cpp/LICENSE`).
