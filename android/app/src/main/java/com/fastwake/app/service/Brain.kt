package com.fastwake.app.service

import com.fastwake.app.EngineResult

/** What the assistant is doing right now. */
enum class Phase {
    /** Waiting for "Hey Ordinary". */
    LISTENING,
    /** Heard the wake word alone; waiting for the question. */
    AWAITING_QUESTION,
    /** Question sent to Claude; waiting for the first words of the answer. */
    THINKING,
    /** Reading the answer aloud. */
    SPEAKING,
    /** Answer finished; a follow-up can be asked without the wake word. */
    FOLLOW_UP,
}

/**
 * The conversation state machine, with no Android dependencies so it can be
 * unit-tested (BrainTest). The service feeds it engine results and events,
 * and it calls back through [Actions].
 */
class Brain(
    private val clock: () -> Long,
    private val followUpEnabled: () -> Boolean,
    private val actions: Actions,
) {
    interface Actions {
        fun phaseChanged(phase: Phase, detail: String)
        fun chimeWake()
        /** Start answering [question] (async). Must later lead to [onSpeechDone]. */
        fun ask(question: String)
        /** Clear the engine's buffered audio (e.g. so the answer we just played isn't transcribed). */
        fun resetEngine()
        /** Long pause since the last question: start a fresh conversation. */
        fun newConversation()
    }

    companion object {
        const val AWAIT_QUESTION_MS = 8_000L
        const val FOLLOW_UP_MS = 7_000L
        const val NEW_CONVERSATION_AFTER_MS = 3 * 60_000L

        /** Phrases Whisper tends to hallucinate from background noise. */
        private val NOISE = setOf("you", "thank you", "thanks", "thanks for watching", "bye", "okay", "hmm", "uh", "um")

        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").trim().replace(Regex("\\s+"), " ")
    }

    var phase = Phase.LISTENING
        private set
    private var deadline = 0L
    private var lastQuestionAt = Long.MIN_VALUE / 2

    /** Whether microphone audio should be fed to the engine in this phase. */
    val acceptsAudio: Boolean
        get() = phase == Phase.LISTENING || phase == Phase.AWAITING_QUESTION || phase == Phase.FOLLOW_UP

    fun onEngine(r: EngineResult) {
        if (!r.decoded) return
        when (phase) {
            Phase.LISTENING -> if (r.wake) onWake(r.command)
            Phase.AWAITING_QUESTION -> {
                val q = if (r.wake) r.command else r.text
                if (isQuestion(q)) startAsk(q) else if (r.wake) onWake("")
            }
            Phase.FOLLOW_UP -> {
                if (r.wake) { onWake(r.command); return }
                if (isQuestion(r.text) && normalize(r.text) !in NOISE) startAsk(r.text)
            }
            Phase.THINKING, Phase.SPEAKING -> Unit   // mic is ignored while answering
        }
    }

    /** A question typed in the app instead of spoken. */
    fun onTyped(question: String) {
        if (question.isBlank() || phase == Phase.THINKING) return
        startAsk(question.trim())
    }

    /** The first sentence of the answer started playing. */
    fun onSpeakingStarted() {
        if (phase == Phase.THINKING) set(Phase.SPEAKING, "")
    }

    /** The whole answer (or error message) has been spoken. */
    fun onSpeechDone() {
        if (phase != Phase.THINKING && phase != Phase.SPEAKING) return
        actions.resetEngine()
        if (followUpEnabled()) {
            deadline = clock() + FOLLOW_UP_MS
            set(Phase.FOLLOW_UP, "")
        } else {
            set(Phase.LISTENING, "")
        }
    }

    /** Called regularly (a few times per second) to expire waiting windows. */
    fun onTick() {
        if ((phase == Phase.AWAITING_QUESTION || phase == Phase.FOLLOW_UP) && clock() > deadline) {
            actions.resetEngine()
            set(Phase.LISTENING, "")
        }
    }

    /** User pressed stop while it was thinking or speaking. */
    fun cancel() {
        actions.resetEngine()
        set(Phase.LISTENING, "")
    }

    private fun onWake(command: String) {
        if (clock() - lastQuestionAt > NEW_CONVERSATION_AFTER_MS) actions.newConversation()
        if (isQuestion(command)) {
            startAsk(command)
        } else {
            actions.chimeWake()
            deadline = clock() + AWAIT_QUESTION_MS
            set(Phase.AWAITING_QUESTION, "")
        }
    }

    private fun startAsk(question: String) {
        lastQuestionAt = clock()
        set(Phase.THINKING, question)
        actions.ask(question)
    }

    private fun isQuestion(s: String) = normalize(s).any { it.isLetter() }

    private fun set(p: Phase, detail: String) {
        phase = p
        actions.phaseChanged(p, detail)
    }
}
