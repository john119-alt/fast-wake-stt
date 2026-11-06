package com.fastwake.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val RATE = 16000
        const val WAKE_PHRASE = "hey ordinary"
        val MODELS = listOf(
            "ggml-tiny.en-q8_0.bin" to
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin",
            "ggml-silero-v5.1.2.bin" to
                "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
        )
    }

    private lateinit var status: TextView
    private lateinit var wakeBadge: TextView
    private lateinit var log: TextView
    private lateinit var button: Button

    @Volatile private var running = false
    private var engine = 0L
    private val chunks = LinkedBlockingQueue<ShortArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        TextView(this).apply {
            text = "FastWake"; textSize = 26f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; root.addView(this)
        }
        status = TextView(this).apply {
            text = "Say \"Hey Ordinary\" after you press Start."
            textSize = 15f; setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, pad / 2, 0, pad); root.addView(this)
        }
        wakeBadge = TextView(this).apply {
            text = "idle"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(0, pad, 0, pad); root.addView(this)
        }
        button = Button(this).apply {
            text = "Start listening"; textSize = 18f
            setOnClickListener { if (running) stop() else start() }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        }
        log = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#E0E0E0"))
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad })
        setContentView(root)
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun say(s: String) = ui { status.text = s }
    private fun append(s: String) = ui { log.text = "$s\n${log.text}" }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        button.text = "Stop"
        thread(name = "setup") {
            try {
                ensureModels()
                if (engine == 0L) {
                    say("Loading engine…")
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    engine = NativeEngine.create(
                        File(filesDir, MODELS[0].first).path,
                        File(filesDir, MODELS[1].first).path, threads, WAKE_PHRASE)
                    if (engine == 0L) { say("Engine failed to load."); stopUi(); return@thread }
                    append("engine ready ($threads threads)")
                }
                say("Listening… say \"Hey Ordinary\"")
                ui { wakeBadge.text = "listening"; wakeBadge.setBackgroundColor(Color.parseColor("#1565C0")) }
                thread(name = "mic") { recordLoop() }
                thread(name = "decode") { decodeLoop() }
            } catch (e: Exception) {
                say("Error: ${e.message}"); stopUi()
            }
        }
    }

    private fun stop() { running = false; stopUi() }

    private fun stopUi() = ui {
        running = false
        button.text = "Start listening"
        wakeBadge.text = "idle"; wakeBadge.setBackgroundColor(Color.parseColor("#37474F"))
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) start()
        else say("Microphone permission is needed.")
    }

    /** Download the official whisper.cpp models once, into app storage. */
    private fun ensureModels() {
        for ((name, url) in MODELS) {
            val f = File(filesDir, name)
            if (f.exists() && f.length() > 0) continue
            say("Downloading $name…")
            val tmp = File(filesDir, "$name.part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); done += n
                        if (total > 0) say("Downloading $name… ${done * 100 / total}%")
                    }
                }
            }
            tmp.renameTo(f)
        }
    }

    /** Capture 16 kHz mono PCM and hand it off in 1-second chunks. */
    private fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE * 2))
        val chunk = ShortArray(RATE)
        rec.startRecording()
        try {
            while (running) {
                var filled = 0
                while (running && filled < chunk.size) {
                    val n = rec.read(chunk, filled, chunk.size - filled)
                    if (n > 0) filled += n
                }
                if (filled > 0) chunks.put(chunk.copyOf(filled))
            }
        } finally { rec.stop(); rec.release() }
    }

    /** Feed audio to the engine. If decoding falls behind, merge queued chunks. */
    private fun decodeLoop() {
        while (running) {
            val first = chunks.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val pending = mutableListOf(first)
            chunks.drainTo(pending)
            val pcm = ShortArray(pending.sumOf { it.size })
            var off = 0
            for (c in pending) { c.copyInto(pcm, off); off += c.size }

            val parts = NativeEngine.feed(engine, pcm, pcm.size).split("\t", limit = 3)
            val woke = parts.getOrNull(0) == "1"
            val ms = parts.getOrNull(1) ?: "?"
            val text = parts.getOrNull(2)?.trim().orEmpty()
            if (text.isNotEmpty()) append("[$ms ms] $text")
            if (woke) {
                append(">>> WAKE WORD DETECTED")
                NativeEngine.reset(engine)   // clear the window so it doesn't refire
                ui {
                    wakeBadge.text = "HEY ORDINARY ✓"
                    wakeBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        if (engine != 0L) { NativeEngine.free(engine); engine = 0L }
        super.onDestroy()
    }
}
