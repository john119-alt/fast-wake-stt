package com.fastwake.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.fastwake.app.NativeEngine
import com.fastwake.app.R
import com.fastwake.app.assistant.Assistant
import com.fastwake.app.assistant.AnswerEvents
import com.fastwake.app.assistant.ClaudeAssistant
import com.fastwake.app.assistant.DeviceContext
import com.fastwake.app.assistant.SentenceChunker
import com.fastwake.app.data.HistoryStore
import com.fastwake.app.data.ModelStore
import com.fastwake.app.data.Prefs
import com.fastwake.app.data.SecureStore
import com.fastwake.app.speech.Chime
import com.fastwake.app.speech.Speaker
import com.fastwake.app.ui.MainActivity
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Always-on assistant, running as a microphone foreground service so it keeps
 * listening with the screen off ("FastWake is listening" notification).
 *
 * Threads:
 *  - mic:    reads 100 ms PCM chunks into [events]
 *  - brain:  the only thread that touches the engine and [Brain]; handles
 *            everything in [events] in order
 *  - answer: runs one Claude request at a time and feeds the speaker
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "FastWake"
        const val ACTION_STOP = "com.fastwake.app.STOP"
        const val ACTION_ASK = "com.fastwake.app.ASK"
        const val ACTION_CANCEL = "com.fastwake.app.CANCEL"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 1
        private const val RATE = 16000
        const val WAKE_PHRASE = "hey ordinary"
        private const val WAKE_LOCK_MS = 30 * 60_000L

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AssistantService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))

        fun ask(context: Context, text: String) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_ASK).putExtra(EXTRA_TEXT, text))

        fun cancel(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_CANCEL))
    }

    /** Everything the brain thread reacts to, in arrival order. */
    private sealed class Event {
        class Audio(val pcm: ShortArray) : Event()
        class Typed(val text: String) : Event()
        object SpeakingStarted : Event()
        object SpeechDone : Event()
        object Cancel : Event()
    }

    @Volatile private var running = false
    /** Bumped on each new question or cancel; a stale answer checks it and stays silent. */
    @Volatile private var answerGen = 0
    private val events = LinkedBlockingQueue<Event>()
    private var engine = 0L
    private var assistant: Assistant? = null
    private lateinit var prefs: Prefs
    private lateinit var history: HistoryStore
    private lateinit var speaker: Speaker
    private val chime = Chime()
    private val answerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "answer") }
    private var micThread: Thread? = null
    private var brainThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockRenew = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        history = HistoryStore(this)
        speaker = Speaker(this) { events.put(Event.SpeechDone) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ASK -> { intent.getStringExtra(EXTRA_TEXT)?.let { events.put(Event.Typed(it)) }; return START_NOT_STICKY }
            ACTION_CANCEL -> {
                answerGen++                    // the running answer may no longer speak
                speaker.stop(notify = false)
                AssistantState.clearStreaming()
                AssistantState.setStatus("")
                events.put(Event.Cancel)
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        startInForeground()
        running = true
        AssistantState.setRunning(true)
        // Keep the CPU awake while listening with the screen off. Timed and
        // renewed from the brain loop, so a crash can never leave it held.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastWake:listening").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        brainThread = thread(name = "brain") { runBrain() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        speaker.stop()
        micThread?.join(1500)
        brainThread?.join(3000)
        answerThread.shutdownNow()
        if (engine != 0L) { NativeEngine.free(engine); engine = 0L }
        runCatching { assistant?.close() }
        speaker.shutdown()
        chime.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        AssistantState.setRunning(false)
        AssistantState.setHearing(false)
        AssistantState.setStatus("")
        super.onDestroy()
    }

    // --- foreground notification ---------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while FastWake is listening for its wake word."
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle("FastWake is listening")
            .setContentText("Say \"Hey Ordinary\" to ask anything.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // --- setup + brain loop -----------------------------------------------------------

    private fun runBrain() {
        try {
            setUp()
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            AssistantState.setStatus("Couldn't start: ${e.message ?: "unknown error"}. Check your internet connection and try again.")
            stopSelf()
            return
        }

        val brain = Brain(System::currentTimeMillis, { prefs.followUp }, object : Brain.Actions {
            override fun phaseChanged(phase: Phase, detail: String) {
                AssistantState.setPhase(phase)
                if (phase == Phase.LISTENING || phase == Phase.FOLLOW_UP) AssistantState.setStatus("")
            }
            override fun chimeWake() { if (prefs.chime) chime.wake() }
            override fun ask(question: String) = startAnswer(question)
            override fun resetEngine() = NativeEngine.reset(engine)
            override fun newConversation() {
                assistant?.newConversation()
                history.newSession()
                AssistantState.clearTurns()
            }
        })
        AssistantState.setPhase(brain.phase)
        AssistantState.setStatus(if (assistant == null) "Add your Claude API key in Settings to get answers." else "")
        micThread = thread(name = "mic") { runMic() }

        while (running) {
            when (val ev = events.poll(200, TimeUnit.MILLISECONDS)) {
                null -> Unit
                is Event.Audio -> if (brain.acceptsAudio) {
                    val r = NativeEngine.feedAudio(engine, ev.pcm)
                    AssistantState.setHearing(r.speaking)
                    if (r.decoded) Log.i(TAG, "heard (${r.decodeMs} ms, conf ${r.conf}): ${r.text}")
                    brain.onEngine(r)
                } else AssistantState.setHearing(false)
                is Event.Typed -> brain.onTyped(ev.text)
                Event.SpeakingStarted -> brain.onSpeakingStarted()
                Event.SpeechDone -> brain.onSpeechDone()
                Event.Cancel -> brain.cancel()
            }
            brain.onTick()
            val now = System.currentTimeMillis()
            if (now - lastWakeLockRenew > WAKE_LOCK_MS / 2) {
                lastWakeLockRenew = now
                wakeLock?.acquire(WAKE_LOCK_MS)
            }
        }
    }

    private fun setUp() {
        val models = ModelStore(this)
        if (!models.ready) models.ensure { AssistantState.setStatus(it) }
        AssistantState.setStatus("Loading speech engine…")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        engine = NativeEngine.create(models.path(ModelStore.WHISPER), models.path(ModelStore.SILERO), threads, WAKE_PHRASE)
        check(engine != 0L) { "speech engine failed to load" }

        assistant = SecureStore(this).apiKey?.let { key ->
            ClaudeAssistant(apiKey = key, model = prefs.model, onError = { Log.e(TAG, "assistant error", it) }) {
                DeviceContext(ZonedDateTime.now(), Locale.getDefault().country)
            }
        }
        speaker.rate = prefs.speechRate
        history.newSession()
    }

    private fun runMic() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2))
        } catch (e: SecurityException) {
            AssistantState.setStatus("Microphone permission is needed."); stopSelf(); return
        }
        val chunk = ShortArray(RATE / 10)
        rec.startRecording()
        try {
            while (running) {
                var filled = 0
                while (running && filled < chunk.size) {
                    val n = rec.read(chunk, filled, chunk.size - filled)
                    if (n > 0) filled += n else if (n < 0) break
                }
                if (filled > 0) events.put(Event.Audio(chunk.copyOf(filled)))
            }
        } finally {
            rec.stop(); rec.release()
        }
    }

    // --- answering ---------------------------------------------------------------------

    private fun startAnswer(question: String) {
        val gen = ++answerGen
        speaker.stop(notify = false)          // cut off any previous answer still playing
        AssistantState.addTurn(Turn(fromUser = true, text = question))
        AssistantState.clearStreaming()
        history.add("user", question)
        speaker.rate = prefs.speechRate
        speaker.beginAnswer()

        val current = assistant
        if (current == null) {
            sayError("I need a Claude API key first. Open Settings in the app to add one.")
            return
        }

        answerThread.execute {
            var started = false
            val chunker = SentenceChunker { sentence ->
                if (gen != answerGen) return@SentenceChunker
                if (!started) { started = true; events.put(Event.SpeakingStarted) }
                speaker.speak(sentence)
            }
            val answer = current.ask(question, object : AnswerEvents {
                override fun onSearching() { if (gen == answerGen) AssistantState.setStatus("Searching the web…") }
                override fun onText(delta: String) {
                    if (gen != answerGen) return
                    AssistantState.appendStreaming(delta)
                    chunker.add(delta)
                }
            })
            if (gen != answerGen) return@execute   // cancelled or superseded: stay silent
            AssistantState.setStatus("")
            if (answer.ok) {
                chunker.finish()
                val meta = buildString {
                    if (answer.firstTextMs >= 0) append("first words in ${"%.1f".format(answer.firstTextMs / 1000.0)} s")
                    if (answer.searches.isNotEmpty()) append(" · searched: ${answer.searches.joinToString("; ")}")
                }
                AssistantState.clearStreaming()
                AssistantState.addTurn(Turn(fromUser = false, text = answer.text, meta = meta))
                history.add("assistant", answer.text, meta)
                speaker.endOfAnswer()
            } else {
                // Partial text from a failed stream isn't kept; say what went wrong instead.
                speaker.stop(notify = false)
                speaker.beginAnswer()
                AssistantState.clearStreaming()
                sayError(answer.text)
            }
        }
    }

    private fun sayError(text: String) {
        chime.error()
        AssistantState.addTurn(Turn(fromUser = false, text = text, meta = "error"))
        history.add("assistant", text, "error")
        events.put(Event.SpeakingStarted)
        speaker.speak(text)
        speaker.endOfAnswer()
    }
}
