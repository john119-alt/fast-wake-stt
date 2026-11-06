package com.fastwake.app.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentLinkedQueue

class GeminiAssistantTest {

    private val json = ObjectMapper()
    private val server = MockWebServer()
    private val calls = ConcurrentLinkedQueue<Pair<String, JsonNode>>()   // (path+query, body)
    private val replies = ArrayDeque<MockResponse>()

    @Before fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request.path.orEmpty() to json.readTree(request.body.readUtf8())
                return synchronized(replies) { replies.removeFirst() }
            }
        }
        server.start()
    }

    @After fun stopServer() = server.shutdown()

    /** LiveData that never touches the network. */
    private class FakeLive(private val ctx: String) : LiveData() {
        override fun contextFor(question: String, homeCity: String) = ctx
    }

    private fun assistant(speed: String = GeminiAssistant.SPEED_FAST, live: String = "") = GeminiAssistant(
        apiKey = "AIza-test", speed = speed, baseUrl = server.url("/").toString().trimEnd('/'),
        liveData = FakeLive(live),
    ) { DeviceContext(ZonedDateTime.of(2026, 11, 6, 13, 5, 0, 0, ZoneId.of("Asia/Kolkata")), "IN") }

    private fun sse(vararg chunks: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\r\n\r\n" })

    private fun textChunk(t: String, finish: String? = null, signature: String? = null): String {
        val part = mutableMapOf<String, Any>("text" to t)
        if (signature != null) part["thoughtSignature"] = signature
        val cand = mutableMapOf<String, Any>("content" to mapOf("role" to "model", "parts" to listOf(part)), "index" to 0)
        if (finish != null) cand["finishReason"] = finish
        return json.writeValueAsString(mapOf("candidates" to listOf(cand)))
    }

    private fun error(code: Int, status: String, message: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$code,"message":"$message","status":"$status"}}""")

    private fun reply(r: MockResponse) = synchronized(replies) { replies.addLast(r) }

    private class Rec : AnswerEvents {
        val text = StringBuilder()
        override fun onText(delta: String) { text.append(delta) }
    }

    @Test fun `streams text and sends the expected request`() {
        reply(sse(textChunk("It's 31 degrees "), textChunk("and sunny.", "STOP")))
        val rec = Rec()
        val a = assistant().ask("what's the weather", rec)

        assertTrue(a.detail, a.ok)
        assertEquals("It's 31 degrees and sunny.", a.text)
        assertEquals("It's 31 degrees and sunny.", rec.text.toString())
        assertEquals("gemini-3.5-flash-lite", a.detail)

        val (path, body) = calls.single()
        assertTrue(path, path.startsWith("/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent?"))
        assertTrue(path, path.contains("alt=sse") && path.contains("key=AIza-test"))
        assertEquals("MINIMAL", body["generationConfig"]["thinkingConfig"]["thinkingLevel"].asText())
        assertTrue(body["systemInstruction"]["parts"][0]["text"].asText().contains("Friday 6 November 2026"))
        assertEquals("user", body["contents"][0]["role"].asText())
        assertEquals("what's the weather", body["contents"][0]["parts"][0]["text"].asText())
    }

    @Test fun `smart mode uses flash with low thinking`() {
        reply(sse(textChunk("Hi.", "STOP")))
        assistant(GeminiAssistant.SPEED_SMART).ask("hi", Rec())
        val (path, body) = calls.single()
        assertTrue(path, path.contains("/models/gemini-3.8-flash:"))
        assertEquals("LOW", body["generationConfig"]["thinkingConfig"]["thinkingLevel"].asText())
    }

    @Test fun `live data is put in front of the question`() {
        reply(sse(textChunk("Sunny, 31 degrees.", "STOP")))
        val a = assistant(live = "Live weather for Mumbai, India (Open-Meteo, local time x):\nNow: 31°C").ask("weather in Mumbai", Rec())
        val sent = calls.single().second["contents"][0]["parts"][0]["text"].asText()
        assertTrue(sent, sent.startsWith("Live data the app fetched for this question:"))
        assertTrue(sent, sent.endsWith("Question: weather in Mumbai"))
        assertEquals(listOf("Live weather for Mumbai, India"), a.searches)
    }

    @Test fun `follow-up resends history with the thought signature`() {
        val asst = assistant()
        reply(sse(textChunk("Sunny.", null), textChunk("", "STOP", signature = "sig-abc")))
        asst.ask("weather?", Rec())
        reply(sse(textChunk("Rain later.", "STOP")))
        assertTrue(asst.ask("and later?", Rec()).ok)

        val contents = calls.last().second["contents"]
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"].asText() })
        assertEquals("Sunny.", contents[1]["parts"][0]["text"].asText())
        assertEquals("sig-abc", contents[1]["parts"][0]["thoughtSignature"].asText())
    }

    @Test fun `unavailable model falls back to the next free model`() {
        reply(error(404, "NOT_FOUND", "models/gemini-3.5-flash-lite is not found for API version v1beta"))
        reply(sse(textChunk("Hello!", "STOP")))
        val asst = assistant()
        val a = asst.ask("hi", Rec())
        assertTrue(a.detail, a.ok)
        assertEquals("gemini-3.1-flash-lite", a.detail)
        // remembered for the next question
        reply(sse(textChunk("Again.", "STOP")))
        asst.ask("hi again", Rec())
        assertTrue(calls.last().first.contains("/models/gemini-3.1-flash-lite:"))
    }

    @Test fun `thinking setting rejected - retries without it`() {
        reply(error(400, "INVALID_ARGUMENT", "Thinking level is not supported for this model."))
        reply(sse(textChunk("OK.", "STOP")))
        val a = assistant().ask("hi", Rec())
        assertTrue(a.detail, a.ok)
        assertFalse(calls.last().second["generationConfig"].has("thinkingConfig"))
    }

    @Test fun `bad key is explained and shows google's message`() {
        reply(error(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."))
        val a = assistant().ask("hi", Rec())
        assertFalse(a.ok)
        assertTrue(a.text, a.text.contains("API key"))
        assertTrue(a.detail, a.detail.contains("API key not valid"))
    }

    @Test fun `quota exceeded explains the free limit`() {
        reply(error(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota."))
        val a = assistant().ask("hi", Rec())
        assertFalse(a.ok)
        assertTrue(a.text, a.text.contains("free Gemini limit"))
    }

    @Test fun `server error is retried once`() {
        reply(error(503, "UNAVAILABLE", "The model is overloaded."))
        reply(sse(textChunk("Fine.", "STOP")))
        assertTrue(assistant().ask("hi", Rec()).ok)
        assertEquals(2, calls.size)
    }

    @Test fun `plain JSON array reply is accepted`() {
        reply(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("[" + textChunk("Part one. ") + "," + textChunk("Part two.", "STOP") + "]"))
        val a = assistant().ask("hi", Rec())
        assertTrue(a.detail, a.ok)
        assertEquals("Part one. Part two.", a.text)
    }

    @Test fun `thought parts are not spoken`() {
        val thought = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Thinking about it","thought":true}]}}]}"""
        reply(sse(thought, textChunk("Answer.", "STOP")))
        val rec = Rec()
        val a = assistant().ask("hi", rec)
        assertEquals("Answer.", a.text)
        assertEquals("Answer.", rec.text.toString())
    }

    @Test fun `blocked prompt gives a polite answer and is not kept`() {
        reply(sse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        val asst = assistant()
        val a = asst.ask("bad", Rec())
        assertFalse(a.ok)
        assertTrue(a.text, a.text.contains("can't help"))
        reply(sse(textChunk("Sure.", "STOP")))
        asst.ask("next", Rec())
        assertEquals(1, calls.last().second["contents"].size())
    }
}
