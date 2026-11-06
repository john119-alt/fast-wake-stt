package com.fastwake.app.speech

import android.media.AudioManager
import android.media.ToneGenerator

/** Short audio cues so you know the assistant heard you without looking. */
class Chime {
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    /** Wake word heard: listening for your question. */
    fun wake() { tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }

    /** Something went wrong (no key, no internet, ...). */
    fun error() { tones?.startTone(ToneGenerator.TONE_PROP_NACK, 200) }

    fun release() { tones?.release() }
}
