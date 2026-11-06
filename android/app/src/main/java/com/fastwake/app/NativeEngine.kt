package com.fastwake.app

/** Result of one feed()/flush() call. */
data class EngineResult(
    val decoded: Boolean,     // an utterance was transcribed in this call
    val speaking: Boolean,    // voice detector is currently inside speech
    val wake: Boolean,        // wake phrase found with enough confidence
    val conf: Float,          // wake-phrase confidence (0 if no match)
    val decodeMs: Int,        // Whisper time for this call
    val audioMs: Int,         // length of speech transcribed
    val text: String,
) {
    companion object {
        fun parse(s: String): EngineResult {
            val p = s.split("\t", limit = 7)
            return EngineResult(
                decoded  = p.getOrNull(0) == "1",
                speaking = p.getOrNull(1) == "1",
                wake     = p.getOrNull(2) == "1",
                conf     = p.getOrNull(3)?.toFloatOrNull() ?: 0f,
                decodeMs = p.getOrNull(4)?.toIntOrNull() ?: 0,
                audioMs  = p.getOrNull(5)?.toIntOrNull() ?: 0,
                text     = p.getOrNull(6)?.trim().orEmpty(),
            )
        }
    }
}

object NativeEngine {
    init { System.loadLibrary("fastwake") }

    external fun create(model: String, vad: String, threads: Int, phrase: String): Long
    private external fun feed(handle: Long, pcm: ShortArray, n: Int): String
    private external fun flush(handle: Long): String
    external fun reset(handle: Long)
    external fun free(handle: Long)

    fun feedAudio(handle: Long, pcm: ShortArray): EngineResult =
        EngineResult.parse(feed(handle, pcm, pcm.size))

    fun flushAudio(handle: Long): EngineResult = EngineResult.parse(flush(handle))
}
