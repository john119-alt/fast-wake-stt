package com.fastwake.app.assistant

/**
 * The assistant's "brain". The app talks only to this interface, so the
 * current direct-to-Claude implementation ([ClaudeAssistant]) can later be
 * swapped for one that goes through your own server without touching the
 * service or UI.
 */
interface Assistant : AutoCloseable {
    /**
     * Answer one spoken question in the context of the current conversation.
     * Blocking: call it off the main thread. Partial text arrives through
     * [events] while the answer streams.
     */
    fun ask(question: String, events: AnswerEvents): Answer

    /** Forget the conversation so the next question starts fresh. */
    fun newConversation()
}

interface AnswerEvents {
    /** The model started a web search. */
    fun onSearching() {}
    /** A piece of the spoken answer arrived. */
    fun onText(delta: String) {}
}

data class Answer(
    /** Full answer text (or a short spoken error message when [ok] is false). */
    val text: String,
    val ok: Boolean,
    /** Web searches the model ran, for display. */
    val searches: List<String> = emptyList(),
    /** Time from request to first spoken word, for display. */
    val firstTextMs: Long = -1,
    val totalMs: Long = 0,
)
