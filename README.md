# fast-wake-stt

A clean-room **offline wake-word + speech-to-text engine** in C++ (whisper.cpp +
Silero VAD). It's a reference you own, inspired by the *architecture* of the
"Ordi" app's native engine but rebuilt with four concrete speed/quality
improvements. None of Ordi's code is used.

## What it does
Streams 16 kHz mono PCM through a fixed ring buffer, runs **VAD-gated** Whisper
transcription (only speech is decoded), and reports whether a wake phrase
("hey ordinary" by default) was spoken — plus a real-time-factor (RTF)
benchmark so you can measure "faster."

## The four improvements over Ordi's engine
| # | Ordi (from decompilation) | Here |
|---|---|---|
| 1 | `whisper_context_default_params` with **`use_gpu = 0`** (CPU only) | `use_gpu` is configurable; build with `-DWAKE_ENABLE_VULKAN=ON` for a GPU backend |
| 2 | `ordi_wake_feed` **reallocated a growing `std::vector<float>`** every call on the always-on path | `RingBuffer<float>` allocated **once**, no per-feed malloc |
| 3 | thread count **hardcoded to 2** | `n_threads` configurable (CLI arg) |
| 4 | reply waited on the full backend round-trip | streaming design — see "Streaming" below |

## Get the code
```bash
git clone --recursive https://github.com/<you>/fast-wake-stt.git
# already cloned without --recursive?
git submodule update --init
```

## Files
```
src/ring_buffer.h       fixed-capacity circular audio buffer (improvement #2)
src/wake_engine.h/.cpp  the engine (init / feed / reset)
src/main.cpp            CLI: load WAV, stream in 1s chunks, print transcript + RTF
CMakeLists.txt          desktop build; Vulkan toggle (improvement #1)
android/                FastWake Android app (Kotlin + JNI, arm64)
test-audio/             wake / no-wake / long-speech test clips
vendor/whisper.cpp      upstream whisper.cpp, git submodule pinned to 727d3e7
```

## Android app (FastWake)
Install the APK from the GitHub Releases page on an arm64 phone running
Android 9 or newer. On first launch it downloads the two official models
(~44 MB) into app storage. Press **Start listening**, then say "Hey Ordinary".
Each decode pass shows its time in ms, and the badge turns green on a wake.

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

## Measured results (v0.1, 4-core Intel Xeon 2.1 GHz, CPU only, tiny.en q8_0)
Streaming each clip in 1 s chunks; "total compute" is every decode pass summed.

| Clip | Transcript | Wake | Total compute | Stream speed |
|---|---|---|---|---|
| hey.wav (4.1 s, "Hey Ordinary, remind me…") | remind me to call mom tomorrow… | **YES** | 1,265 ms | 3.2× real-time |
| nowake.wav (3.7 s, unrelated speech) | I am just talking to my friend… | no | 978 ms | 3.8× real-time |
| jfk.wav (11 s) | ask not what your country can do… | no | 3,116 ms | 3.5× real-time |

Threads (lever #3), jfk.wav: 1 → 8,759 ms · **2 (Ordi) → 5,112 ms** · **4 → 3,347 ms** (−35%).
A single decode of an 8 s window takes ~260 ms (≈30× real-time).
GPU (lever #1) not measured: this machine has no GPU.

Wake detection uses an `initial_prompt` bias plus fuzzy word matching
(punctuation-insensitive, small edit-distance budget), so "Hey, Ordinary" or a
slightly misheard "Ordinery" still triggers.

## Test it with your own voice
Record yourself saying "Hey Ordinary, …" on your phone, copy it over, then:
```bash
ffmpeg -i my_recording.m4a -ar 16000 -ac 1 -sample_fmt s16 me.wav
./build/wake_stt models/ggml-tiny.en-q8_0.bin models/ggml-silero-v5.1.2.bin me.wav 4 0
```

## Known next optimization
The engine re-decodes the whole window on every 1 s feed, so streaming cost is
~10× a single decode. Next step: decode only when Silero VAD reports the end of a
speech segment, and only that segment. That should bring streaming close to the
~30× single-pass speed and cut battery use while idle.

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
`n_threads` (match performance cores), `vad_threshold` (Silero sensitivity),
`window_ms` (sliding window), model/quantization (tiny.en q8_0 → q5_1/q4_0 for
speed, base.en for accuracy).

## License
Your code under this repo; whisper.cpp under its MIT license (see
`vendor/whisper.cpp/LICENSE`).
