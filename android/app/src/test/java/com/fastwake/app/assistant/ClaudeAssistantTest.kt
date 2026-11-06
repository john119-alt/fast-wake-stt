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

/**
 * Exercises ClaudeAssistant end to end against a local mock of the Messages
 * API that speaks the real server-sent-events protocol. Checks what the app
 * sends (model, tools, fallbacks, history) and how it handles each kind of
 * reply. Everything except the network itself.
 */
class ClaudeAssistantTest {

    private val json = ObjectMapper()
    private val server = MockWebServer()
    private val requests = ConcurrentLinkedQueue<Captured>()
    /** Responses served in order: an SSE body (200) or an error (status, body). */
    private val replies = ArrayDeque<Pair<Int, String>>()

    data class Captured(val body: JsonNode, val beta: String?, val apiKey: String?)   // apiKey = Authorization header

    @Before fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += Captured(json.readTree(request.body.readUtf8()),
                    request.getHeader("anthropic-beta"), request.getHeader("Authorization"))
                val (status, payload) = synchronized(replies) { replies.removeFirst() }
                return MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", if (status == 200) "text/event-stream" else "application/json")
                    .setBody(payload)
            }
        }
        server.start()
    }

    @After fun stopServer() = server.shutdown()

    private fun assistant(model: String = "claude-opus-5-5") = ClaudeAssistant(
        apiKey = "sk-ant-api03-test", model = model, baseUrl = server.url("/").toString().trimEnd('/'),
    ) { DeviceContext(ZonedDateTime.of(2026, 11, 6, 13, 5, 0, 0, ZoneId.of("Asia/Kolkata")), "IN") }

    // --- SSE builders ---------------------------------------------------------
    private fun ev(type: String, data: String) = "event: $type\ndata: $data\n\n"
    private fun msgStart() = ev("message_start",
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""")
    private fun text(i: Int, vararg parts: String) =
        ev("content_block_start", """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""") +
        parts.joinToString("") { ev("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":${json.writeValueAsString(it)}}}""") } +
        ev("content_block_stop", """{"type":"content_block_stop","index":$i}""")
    private fun search(i: Int, query: String) =
        ev("content_block_start", """{"type":"content_block_start","index":$i,"content_block":{"type":"server_tool_use","id":"srvtoolu_$i","name":"web_search","input":{}}}""") +
        ev("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":${json.writeValueAsString("{\"query\": \"$query\"}")}}}""") +
        ev("content_block_stop", """{"type":"content_block_stop","index":$i}""") +
        ev("content_block_start", """{"type":"content_block_start","index":${i + 1},"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_$i","content":[{"type":"web_search_result","title":"Mumbai weather","url":"https://example.com/w","encrypted_content":"enc123","page_age":null}]}}""") +
        ev("content_block_stop", """{"type":"content_block_stop","index":${i + 1}}""")
    private fun end(stop: String) =
        ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":12}}""") +
        ev("message_stop", """{"type":"message_stop"}""")

    private fun reply(sse: String) = synchronized(replies) { replies.addLast(200 to sse) }

    private class Recorder : AnswerEvents {
        var searching = 0; val text = StringBuilder()
        override fun onSearching() { searching++ }
        override fun onText(delta: String) { text.append(delta) }
    }

    // --- tests ------------------------------------------------------------------

    @Test fun `web search answer streams text and sends the right request`() {
        reply(msgStart() + search(0, "mumbai weather today") + text(2, "It's 31 degrees ", "and sunny. ") + end("end_turn"))
        val rec = Recorder()
        val a = assistant().ask("what's the weather in Mumbai", rec)

        assertTrue(a.ok)
        assertEquals("It's 31 degrees and sunny.", a.text)
        assertEquals("It's 31 degrees and sunny. ", rec.text.toString())
        assertEquals(1, rec.searching)
        assertEquals(listOf("mumbai weather today"), a.searches)

        val req = requests.single()
        assertEquals("Bearer sk-ant-api03-test", req.apiKey)   // SDK 3.6 sends API keys as a Bearer token
        assertEquals("claude-opus-5-5", req.body["model"].asText())
        assertTrue(req.body["stream"].asBoolean())
        assertEquals("low", req.body["output_config"]["effort"].asText())
        assertEquals("web_search_20260209", req.body["tools"][0]["type"].asText())
        assertEquals("Asia/Kolkata", req.body["tools"][0]["user_location"]["timezone"].asText())
        assertEquals("IN", req.body["tools"][0]["user_location"]["country"].asText())
        assertEquals("default", req.body["fallbacks"].asText())
        assertTrue(req.beta.orEmpty().contains("server-side-fallback-2026-07-01"))
        assertTrue(req.body["system"].asText().contains("Friday 6 November 2026"))
        assertEquals(1, req.body["messages"].size())
    }

    @Test fun `follow-up resends the earlier answer including its search results`() {
        val asst = assistant()
        reply(msgStart() + search(0, "mumbai weather") + text(2, "Sunny and 31 degrees.") + end("end_turn"))
        asst.ask("weather in Mumbai?", Recorder())
        reply(msgStart() + text(0, "Yes, light rain after six.") + end("end_turn"))
        val a = asst.ask("will it rain later?", Recorder())

        assertTrue(a.ok)
        val msgs = requests.last().body["messages"]
        assertEquals(3, msgs.size())
        assertEquals("user", msgs[0]["role"].asText())
        assertEquals("assistant", msgs[1]["role"].asText())
        val types = msgs[1]["content"].map { it["type"].asText() }
        assertEquals(listOf("server_tool_use", "web_search_tool_result", "text"), types)
        assertEquals("enc123", msgs[1]["content"][1]["content"][0]["encrypted_content"].asText())
        assertEquals("will it rain later?", msgs[2]["content"].let { if (it.isTextual) it.asText() else it[0]["text"].asText() })
    }

    @Test fun `pause_turn is resumed without adding a user message`() {
        reply(msgStart() + search(0, "ipl final score") + end("pause_turn"))
        reply(msgStart() + text(0, "Mumbai won by six runs.") + end("end_turn"))
        val a = assistant().ask("who won the final", Recorder())

        assertTrue(a.ok)
        assertEquals("Mumbai won by six runs.", a.text)
        assertEquals(2, requests.size)
        val second = requests.last().body["messages"]
        assertEquals(listOf("user", "assistant"), second.map { it["role"].asText() })
    }

    @Test fun `refusal is spoken politely and kept out of the history`() {
        val asst = assistant()
        reply(msgStart() + end("refusal"))
        val refused = asst.ask("something not allowed", Recorder())
        assertFalse(refused.ok)
        assertEquals("Sorry, I can't help with that one.", refused.text)

        reply(msgStart() + text(0, "Sure.") + end("end_turn"))
        asst.ask("next question", Recorder())
        assertEquals(1, requests.last().body["messages"].size())   // refused turn not resent
    }

    @Test fun `bad api key gives a spoken explanation instead of crashing`() {
        synchronized(replies) {
            replies.addLast(401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        }
        val a = assistant().ask("hello", Recorder())
        assertFalse(a.ok)
        assertTrue(a.text, a.text.contains("API key"))
    }

    @Test fun `haiku uses the basic web search tool and no fallbacks`() {
        reply(msgStart() + text(0, "Hello!") + end("end_turn"))
        assistant("claude-haiku-5-5").ask("hi", Recorder())
        val req = requests.single()
        assertEquals("web_search_20250305", req.body["tools"][0]["type"].asText())
        assertFalse(req.body.has("fallbacks"))
        assertFalse(req.beta.orEmpty().contains("server-side-fallback"))
    }
}
