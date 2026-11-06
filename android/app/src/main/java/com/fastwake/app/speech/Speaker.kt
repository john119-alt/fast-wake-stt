package com.fastwake.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks an answer sentence by sentence while it streams in.
 *
 * Call [speak] for each sentence, then [endOfAnswer] once the answer is
 * complete; [onFinished] fires after the last sentence has been spoken (or
 * immediately if nothing was queued). [stop] cuts speech off.
 */
class Speaker(context: Context, private val onFinished: () -> Unit) {

    @Volatile private var ready = false
    private val pendingBeforeReady = mutableListOf<String>()
    private val outstanding = AtomicInteger(0)
    @Volatile private var answerComplete = false
    @Volatile private var finishedFired = true
    private val ids = AtomicInteger(0)
    @Volatile var rate: Float = 1.0f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault().takeIf { tts.isLanguageAvailable(it) >= 0 } ?: Locale.US
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            synchronized(pendingBeforeReady) {
                ready = true
                pendingBeforeReady.forEach(::enqueue)
                pendingBeforeReady.clear()
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = utteranceEnded(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = utteranceEnded(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = utteranceEnded(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = utteranceEnded(utteranceId)
        })
    }

    /** First utterance id of the current answer; callbacks for older ones are ignored. */
    @Volatile private var answerFirstId = 1

    /** Start a new answer. */
    fun beginAnswer() {
        answerFirstId = ids.get() + 1
        outstanding.set(0)
        answerComplete = false
        finishedFired = false
    }

    fun speak(sentence: String) {
        synchronized(pendingBeforeReady) {
            if (!ready) { pendingBeforeReady += sentence; outstanding.incrementAndGet(); return }
        }
        outstanding.incrementAndGet()
        enqueue(sentence)
    }

    private fun enqueue(sentence: String) {
        tts.setSpeechRate(rate)
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "u${ids.incrementAndGet()}")
    }

    /** No more sentences are coming for this answer. */
    fun endOfAnswer() {
        answerComplete = true
        maybeFinish()
    }

    /**
     * Cut speech off now. With [notify] the usual [onFinished] fires; without
     * it the current answer is dropped silently (used before starting another).
     */
    fun stop(notify: Boolean = true) {
        if (!notify) finishedFired = true
        synchronized(pendingBeforeReady) { pendingBeforeReady.clear() }
        tts.stop()
        outstanding.set(0)
        answerComplete = true
        maybeFinish()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun utteranceEnded(utteranceId: String?) {
        // Late "stopped" callbacks from an earlier, cut-off answer don't count.
        val n = utteranceId?.removePrefix("u")?.toIntOrNull() ?: return
        if (n < answerFirstId) return
        if (outstanding.get() > 0) outstanding.decrementAndGet()
        maybeFinish()
    }

    @Synchronized private fun maybeFinish() {
        if (answerComplete && outstanding.get() == 0 && !finishedFired) {
            finishedFired = true
            onFinished()
        }
    }
}
