package com.fastwake.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextTest {

    private fun chunk(vararg deltas: String, minChars: Int = 24): List<String> {
        val out = mutableListOf<String>()
        val c = SentenceChunker(minChars) { out += it }
        deltas.forEach(c::add)
        c.finish()
        return out
    }

    @Test fun `emits each sentence once the next one starts streaming`() {
        val out = mutableListOf<String>()
        val c = SentenceChunker { out += it }
        c.add("It's 31 degrees and sunny in Mumbai right now. ")
        assertEquals(emptyList<String>(), out)   // waits to see the next word ("e.g. so" vs "Dr. Smith")
        c.add("Expect rain after ")
        assertEquals(listOf("It's 31 degrees and sunny in Mumbai right now."), out)   // before finish()
        c.add("six this evening.")
        c.finish()
        assertEquals("Expect rain after six this evening.", out.last())
        assertEquals(2, out.size)
    }

    @Test fun `does not split decimals or abbreviations`() {
        assertEquals(
            listOf("The rupee is at 83.25 to the dollar, e.g. slightly weaker than last week."),
            chunk("The rupee is at 83.25 to the dollar, e.g. slightly weaker than last week."),
        )
    }

    @Test fun `short fragments are merged into the next sentence`() {
        assertEquals(listOf("Yes. The match starts at 7:30 tonight on Star Sports."),
            chunk("Yes. The match starts at 7:30 tonight on Star Sports."))
    }

    @Test fun `strips markdown links urls and citations`() {
        assertEquals("India won by 5 wickets, see the scorecard.",
            cleanForSpeech("**India** won by 5 wickets[1], see [the scorecard](https://espn.com/x)."))
        assertEquals("Milk Eggs", cleanForSpeech("- Milk\n- Eggs"))
        assertEquals("Weather Sunny", cleanForSpeech("## Weather\nSunny https://weather.com"))
    }

    @Test fun `history keeps the newest items and starts on a user turn`() {
        val items = listOf("u1", "a1", "u2", "a2", "a2b", "u3", "a3")
        assertEquals(listOf("u3", "a3"), ClaudeAssistant.trimHistory(items, 3) { it.startsWith("u") })
        assertEquals(listOf("u2", "a2", "a2b", "u3", "a3"),
            ClaudeAssistant.trimHistory(items, 5) { it.startsWith("u") })
        assertEquals(items, ClaudeAssistant.trimHistory(items, 10) { it.startsWith("u") })
    }
}
