package com.fastwake.app.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the official on-device speech models once, into app storage. */
class ModelStore(private val context: Context) {

    companion object {
        const val WHISPER = "ggml-tiny.en-q8_0.bin"
        const val SILERO = "ggml-silero-v5.1.2.bin"
        private val SOURCES = mapOf(
            WHISPER to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin",
            SILERO to "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
        )
    }

    fun path(name: String): String = File(context.filesDir, name).path

    val ready: Boolean get() = SOURCES.keys.all { File(context.filesDir, it).let { f -> f.exists() && f.length() > 0 } }

    /** Blocking. [progress] gets human-readable status lines. */
    fun ensure(progress: (String) -> Unit) {
        for ((name, url) in SOURCES) {
            val target = File(context.filesDir, name)
            if (target.exists() && target.length() > 0) continue
            val tmp = File(context.filesDir, "$name.part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPct = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) done * 100 / total else -1
                        if (pct != lastPct) { lastPct = pct; progress("Downloading speech model… $pct%") }
                    }
                }
            }
            if (!tmp.renameTo(target)) error("Could not save $name")
        }
    }
}
