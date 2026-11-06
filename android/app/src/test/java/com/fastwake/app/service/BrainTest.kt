package com.fastwake.app.service

import com.fastwake.app.EngineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {

    private var now = 1_000_000L
    private var followUp = true
    private val asked = mutableListOf<String>()
    private var chimes = 0
    private var resets = 0
    private var newConversations = 0

    private val brain = Brain({ now }, { followUp }, object : Brain.Actions {
        override fun phaseChanged(phase: Phase, detail: String) {}
        override fun chimeWake() { chimes++ }
        override fun ask(question: String) { asked += question }
        override fun resetEngine() { resets++ }
        override fun newConversation() { newConversations++ }
    })

    private fun heard(text: String, wake: Boolean = false, command: String = "") =
        brain.onEngine(EngineResult.EMPTY.copy(decoded = true, wake = wake, command = command, text = text))

    @Test fun `wake word with a question asks right away`() {
        heard("Hey Ordinary, what's the weather?", wake = true, command = "what's the weather?")
        assertEquals(listOf("what's the weather?"), asked)
        assertEquals(Phase.THINKING, brain.phase)
        assertEquals(0, chimes)
    }

    @Test fun `wake word alone chimes and takes the next sentence as the question`() {
        heard("Hey Ordinary.", wake = true)
        assertEquals(1, chimes)
        assertEquals(Phase.AWAITING_QUESTION, brain.phase)
        heard("Who won the cricket match yesterday?")
        assertEquals(listOf("Who won the cricket match yesterday?"), asked)
    }

    @Test fun `ordinary speech is ignored while listening`() {
        heard("I'm just talking to my friend about the football game.")
        assertTrue(asked.isEmpty())
        assertEquals(Phase.LISTENING, brain.phase)
    }

    @Test fun `waiting for a question times out`() {
        heard("Hey Ordinary.", wake = true)
        now += Brain.AWAIT_QUESTION_MS + 1
        brain.onTick()
        assertEquals(Phase.LISTENING, brain.phase)
        heard("random chatter")
        assertTrue(asked.isEmpty())
    }

    @Test fun `follow-up without the wake word after an answer`() {
        heard("Hey Ordinary, weather in Mumbai", wake = true, command = "weather in Mumbai")
        brain.onSpeakingStarted()
        assertEquals(Phase.SPEAKING, brain.phase)
        brain.onSpeechDone()
        assertEquals(Phase.FOLLOW_UP, brain.phase)
        heard("And tomorrow?")
        assertEquals(listOf("weather in Mumbai", "And tomorrow?"), asked)
    }

    @Test fun `noise hallucinations don't trigger a follow-up`() {
        heard("Hey Ordinary, hi", wake = true, command = "hi")
        brain.onSpeechDone()
        heard("Thank you.")
        heard("you")
        assertEquals(listOf("hi"), asked)
        assertEquals(Phase.FOLLOW_UP, brain.phase)
    }

    @Test fun `follow-up window closes`() {
        heard("Hey Ordinary, hi", wake = true, command = "hi")
        brain.onSpeechDone()
        now += Brain.FOLLOW_UP_MS + 1
        brain.onTick()
        assertEquals(Phase.LISTENING, brain.phase)
    }

    @Test fun `follow-up can be turned off`() {
        followUp = false
        heard("Hey Ordinary, hi", wake = true, command = "hi")
        brain.onSpeechDone()
        assertEquals(Phase.LISTENING, brain.phase)
    }

    @Test fun `microphone is ignored while thinking and speaking`() {
        heard("Hey Ordinary, hi", wake = true, command = "hi")
        assertFalse(brain.acceptsAudio)
        heard("Hey Ordinary, something else", wake = true, command = "something else")
        brain.onSpeakingStarted()
        assertFalse(brain.acceptsAudio)
        assertEquals(listOf("hi"), asked)
    }

    @Test fun `engine is reset after speaking so the answer isn't transcribed`() {
        heard("Hey Ordinary, hi", wake = true, command = "hi")
        val before = resets
        brain.onSpeechDone()
        assertEquals(before + 1, resets)
    }

    @Test fun `long pause starts a new conversation, quick follow-ups don't`() {
        heard("Hey Ordinary, hi", wake = true, command = "hi")          // first ever
        assertEquals(1, newConversations)
        brain.onSpeechDone(); now += 60_000; brain.onTick()
        heard("Hey Ordinary, and you?", wake = true, command = "and you?")   // 1 min later
        assertEquals(1, newConversations)
        brain.onSpeechDone(); now += Brain.NEW_CONVERSATION_AFTER_MS + 1; brain.onTick()
        heard("Hey Ordinary, new topic", wake = true, command = "new topic")
        assertEquals(2, newConversations)
    }

    @Test fun `typed question works and is ignored while thinking`() {
        brain.onTyped("what time is it in Tokyo")
        brain.onTyped("second")
        assertEquals(listOf("what time is it in Tokyo"), asked)
    }
}
