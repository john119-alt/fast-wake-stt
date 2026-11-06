package com.fastwake.app

import com.fastwake.app.assistant.AnswerEvents
import com.fastwake.app.assistant.ClaudeAssistant
import com.fastwake.app.assistant.DeviceContext
import com.fastwake.app.assistant.GeminiAssistant
import com.fastwake.app.assistant.LiveData
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.system.exitProcess

/**
 * Smoke test run against the R8-shrunk build of the assistant (scripts/r8-smoke.sh),
 * to prove shrinking didn't remove anything the Claude SDK needs at runtime
 * (Jackson finds classes reflectively). Plain main(), JDK HTTP server, and string
 * checks only, so nothing here depends on the libraries being shrunk. The mock server
 * is a minimal HTTP/1.1 responder on a raw ServerSocket (one request per connection).
 */
object R8Smoke {
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val bodies = mutableListOf<String>()
    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        println("${if (ok) "PASS" else "FAIL"}  $name${if (!ok && detail.isNotEmpty()) "  [$detail]" else ""}")
        if (!ok) failures++
    }

    private fun ev(type: String, data: String) = "event: $type\ndata: $data\n\n"
    private val start = ev("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""")
    private fun text(i: Int, t: String) =
        ev("content_block_start", """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""") +
        ev("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":"$t"}}""") +
        ev("content_block_stop", """{"type":"content_block_stop","index":$i}""")
    private fun search(i: Int) =
        ev("content_block_start", """{"type":"content_block_start","index":$i,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""") +
        ev("content_block_delta", """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"mumbai weather\"}"}}""") +
        ev("content_block_stop", """{"type":"content_block_stop","index":$i}""") +
        ev("content_block_start", """{"type":"content_block_start","index":${i + 1},"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","title":"Weather","url":"https://example.com/w","encrypted_content":"enc123","page_age":null}]}}""") +
        ev("content_block_stop", """{"type":"content_block_stop","index":${i + 1}}""")
    private fun end(stop: String) =
        ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":9}}""") +
        ev("message_stop", """{"type":"message_stop"}""")

    @JvmStatic fun main(args: Array<String>) {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "mock-claude") {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
                        return sb.toString()
                    }
                    line()                                    // request line
                    var length = 0
                    while (true) {
                        val h = line(); if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length); var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                    synchronized(bodies) { bodies += String(body) }
                    val (status, payload) = synchronized(replies) { replies.removeFirst() }
                    val bytes = payload.toByteArray()
                    val type = if (status == 200) "text/event-stream" else "application/json"
                    val head = "HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
        val base = "http://127.0.0.1:${server.localPort}"
        fun asst() = ClaudeAssistant("sk-ant-api03-test", "claude-opus-5-5", base, { it.printStackTrace() }) {
            DeviceContext(ZonedDateTime.of(2026, 11, 6, 13, 0, 0, 0, ZoneId.of("Asia/Kolkata")), "IN")
        }
        val none = object : AnswerEvents {}

        try {
            // 1. web search + streamed answer
            val a = asst()
            replies.addLast(200 to start + search(0) + text(2, "Sunny and 31 degrees.") + end("end_turn"))
            val r1 = a.ask("weather in Mumbai", none)
            check("search answer ok", r1.ok && r1.text == "Sunny and 31 degrees.", r1.text)
            check("search query parsed", r1.searches == listOf("mumbai weather"), r1.searches.toString())
            val b1 = bodies.last()
            check("request: model", b1.contains("\"model\":\"claude-opus-5-5\""))
            check("request: web search tool", b1.contains("\"web_search_20260209\""))
            check("request: fallbacks", b1.contains("\"fallbacks\":\"default\""))
            check("request: effort", b1.contains("\"effort\":\"low\""))

            // 2. follow-up re-serializes the search blocks from history
            replies.addLast(200 to start + text(0, "Rain after six.") + end("end_turn"))
            val r2 = a.ask("and later?", none)
            val b2 = bodies.last()
            check("follow-up ok", r2.ok, r2.text)
            check("history: server_tool_use resent", b2.contains("\"server_tool_use\""))
            check("history: search results resent", b2.contains("\"web_search_tool_result\"") && b2.contains("enc123"))

            // 3. pause_turn resumes
            replies.addLast(200 to start + search(0) + end("pause_turn"))
            replies.addLast(200 to start + text(0, "Done.") + end("end_turn"))
            val r3 = asst().ask("long search", none)
            check("pause_turn resumed", r3.ok && r3.text == "Done.", r3.text)

            // 4. refusal
            replies.addLast(200 to start + end("refusal"))
            val r4 = asst().ask("x", none)
            check("refusal handled", !r4.ok && r4.text.contains("can't help"), r4.text)

            // 5. bad key -> typed UnauthorizedException survives shrinking
            replies.addLast(401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
            val r5 = asst().ask("x", none)
            check("401 mapped to API key message", !r5.ok && r5.text.contains("API key"), r5.text)

            // 6. overloaded (529, retried twice by the SDK, then reported)
            repeat(3) { replies.addLast(529 to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") }
            val r6 = asst().ask("x", none)
            check("529 mapped to busy message", !r6.ok && r6.text.contains("busy"), r6.text)

            // --- Gemini (raw OkHttp + Jackson tree, shrunk) ---
            fun gem(live: LiveData = LiveData(geocodeBase = base, forecastBase = base, wikiBase = base)) =
                GeminiAssistant("AIza-test", GeminiAssistant.SPEED_FAST, base, live, { "" }, { it.printStackTrace() }) {
                    DeviceContext(ZonedDateTime.of(2026, 11, 6, 13, 0, 0, 0, ZoneId.of("Asia/Kolkata")), "IN")
                }
            fun gchunk(t: String, finish: String = "") =
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"$t\"" +
                    (if (finish.isNotEmpty()) "" else "") + "}]}" + (if (finish.isNotEmpty()) ",\"finishReason\":\"$finish\"" else "") + "}]}\r\n\r\n"

            // 7. live weather through LiveData (geocode + forecast) then Gemini stream
            replies.addLast(200 to """{"results":[{"name":"Mumbai","latitude":19.07,"longitude":72.88,"admin1":"Maharashtra","country":"India"}]}""")
            replies.addLast(200 to """{"current":{"time":"2026-11-06T13:00","temperature_2m":31.0,"apparent_temperature":34.0,"relative_humidity_2m":60,"weather_code":1,"wind_speed_10m":9.0,"precipitation":0.0},"daily":{"time":["2026-11-06","2026-11-07"],"weather_code":[1,61],"temperature_2m_max":[33.0,32.0],"temperature_2m_min":[26.0,25.0],"precipitation_probability_max":[5,70]}}""")
            replies.addLast(200 to gchunk("Mainly clear, 31 degrees. ") + gchunk("Rain likely tomorrow.", "STOP"))
            val g1 = gem().ask("what's the weather in Mumbai today", none)
            check("gemini answer ok", g1.ok && g1.text == "Mainly clear, 31 degrees. Rain likely tomorrow.", g1.text + " / " + g1.detail)
            val gb = bodies.last()
            check("gemini request has live weather", gb.contains("Live weather for Mumbai") && gb.contains("light rain"))
            check("gemini request has thinking level", gb.contains("\"thinkingLevel\":\"MINIMAL\""))

            // 8. wikipedia lookup (search + summary) feeds the question
            replies.addLast(200 to """{"query":{"search":[{"title":"Virat Kohli"}]}}""")
            replies.addLast(200 to """{"extract":"Virat Kohli is an Indian international cricketer."}""")
            replies.addLast(200 to gchunk("He's an Indian cricketer.", "STOP"))
            val g2 = gem().ask("who is Virat Kohli", none)
            check("gemini wikipedia answer", g2.ok && bodies.last().contains("Indian international cricketer"), g2.detail)

            // 9. gemini model fallback + key error parsing
            replies.addLast(404 to """{"error":{"code":404,"message":"models/x is not found","status":"NOT_FOUND"}}""")
            replies.addLast(200 to gchunk("Hi.", "STOP"))
            val g3 = gem().ask("hi", none)
            check("gemini 404 falls back to next model", g3.ok && g3.detail == "gemini-3.1-flash-lite", g3.detail)
            replies.addLast(400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
            val g4 = gem().ask("hi", none)
            check("gemini bad key explained", !g4.ok && g4.text.contains("API key") && g4.detail.contains("API key not valid"), g4.detail)
        } catch (t: Throwable) {
            t.printStackTrace()
            check("no exception", false, t.toString())
        } finally {
            server.close()
        }
        println(if (failures == 0) "R8 smoke: all checks passed" else "R8 smoke: $failures failure(s)")
        exitProcess(if (failures == 0) 0 else 1)
    }
}
