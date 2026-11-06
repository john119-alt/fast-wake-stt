package com.fastwake.app

/** Result of one feed()/flush() call into the on-device wake/speech engine. */
data class EngineResult(
    val decoded: Boolean,     // an utterance was transcribed in this call
    val speaking: Boolean,    // voice detector is currently inside speech
    val wake: Boolean,        // wake phrase found with enough confidence
    val conf: Float,          // wake-phrase confidence (0 if no match)
    val decodeMs: Int,        // Whisper time for this call
    val audioMs: Int,         // length of speech transcribed
    val command: String,      // words after the wake phrase ("" if only the wake phrase)
    val text: String,         // full transcript
) {
    companion object {
        val EMPTY = EngineResult(false, false, false, 0f, 0, 0, "", "")

        fun parse(bytes: ByteArray): EngineResult {
            // Invalid UTF-8 from the decoder becomes U+FFFD instead of crashing.
            val p = String(bytes, Charsets.UTF_8).split("\t", limit = 8)
            return EngineResult(
                decoded  = p.getOrNull(0) == "1",
                speaking = p.getOrNull(1) == "1",
                wake     = p.getOrNull(2) == "1",
                conf     = p.getOrNull(3)?.toFloatOrNull() ?: 0f,
                decodeMs = p.getOrNull(4)?.toIntOrNull() ?: 0,
                audioMs  = p.getOrNull(5)?.toIntOrNull() ?: 0,
                command  = p.getOrNull(6)?.trim().orEmpty(),
                text     = p.getOrNull(7)?.trim().orEmpty(),
            )
        }
    }
}

/** JNI bridge to libfastwake.so (src/wake_engine.cpp). Function names must match jni.cpp. */
object NativeEngine {
    init { System.loadLibrary("fastwake") }

    external fun create(model: String, vad: String, threads: Int, phrase: String): Long
    private external fun feed(handle: Long, pcm: ShortArray, n: Int): ByteArray
    private external fun flush(handle: Long): ByteArray
    external fun reset(handle: Long)
    external fun free(handle: Long)

    fun feedAudio(handle: Long, pcm: ShortArray): EngineResult = EngineResult.parse(feed(handle, pcm, pcm.size))
    fun flushAudio(handle: Long): EngineResult = EngineResult.parse(flush(handle))
}
