package com.fastwake.app.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Google Gemini on the free tier (AI Studio key, no billing), over the
 * Gemini Developer API REST endpoint `streamGenerateContent?alt=sse`.
 *
 * Gemini's own Google Search grounding isn't available on the free tier, so
 * live facts come from [LiveData] (Open-Meteo weather, Wikipedia), fetched by
 * the app and placed in the question. That keeps it to one model round trip.
 *
 * Built defensively because it couldn't be tested against the live API:
 * - tries the next free model if one isn't available to the key (404),
 * - retries without the thinking setting if the model rejects it (400),
 * - accepts SSE or a plain JSON reply,
 * - returns the provider's exact error text in [Answer.detail].
 */
class GeminiAssistant(
    private val apiKey: String,
    /** "fast" or "smart"; see [MODELS]. */
    speed: String = SPEED_FAST,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val liveData: LiveData = LiveData(),
    private val homeCity: () -> String = { "" },
    private val onError: (Throwable) -> Unit = {},
    private val context: () -> DeviceContext,
) : Assistant {

    companion object {
        const val SPEED_FAST = "fast"
        const val SPEED_SMART = "smart"

        /** Free-tier models in order of preference (ids from ai.google.dev/pricing, Nov 2026). */
        val MODELS = mapOf(
            SPEED_FAST to listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash-lite"),
            SPEED_SMART to listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.5-flash-lite"),
        )

        /** Lowest thinking level each model supports (docs: 3.x Flash-Lite "minimal", 3.8 Flash "low"). */
        fun thinkingLevel(model: String): String? = when {
            model.startsWith("gemini-2") -> null                  // thinkingLevel errors on pre-3 models
            model.contains("flash-lite") -> "MINIMAL"
            else -> "LOW"
        }

        const val MAX_HISTORY_ITEMS = 16
        private val JSON = ObjectMapper()
        private val JSON_TYPE = "application/json".toMediaType()

        fun systemPrompt(ctx: DeviceContext): String {
            val date = ctx.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH))
            val region = if (ctx.countryCode.isNotEmpty()) " Their country code is ${ctx.countryCode}." else ""
            return """
                You are FastWake, a voice assistant running on the user's phone. Everything you write is read aloud by text-to-speech, and the user's words reach you through speech recognition.

                How to answer:
                - Answer in one to three short sentences unless the user asks for more detail. Lead with the answer itself.
                - Write plain spoken English only: no markdown, bullet points, headings, emojis, or URLs.
                - Write numbers, times, units, and currencies the way a person would say them out loud.
                - Some questions arrive with live data the app fetched (weather, Wikipedia). Use it when relevant and don't mention where it came from unless asked.
                - You can't browse the web. For news, live scores, prices, or anything after your knowledge that wasn't provided, say briefly that you can't check live information for that yet, and give what general knowledge you can.
                - Speech recognition sometimes garbles words. If a question doesn't make sense, make your best guess when it's obvious, otherwise ask one short clarifying question.
                - The user can reply without repeating the wake word, so end naturally; don't ask "anything else?" after every answer.

                Context: it is $date (${ctx.now.zone.id}) for the user.$region
            """.trimIndent()
        }

        /** "Live data … Question: …" — what's actually sent as the user's turn. */
        fun withContext(question: String, live: String): String =
            if (live.isBlank()) question
            else "Live data the app fetched for this question:\n$live\n\nQuestion: $question"
    }

    private val models = MODELS[speed] ?: MODELS.getValue(SPEED_FAST)
    @Volatile private var modelIndex = 0
    @Volatile private var thinkingAllowed = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Conversation as Gemini "contents" items. */
    private val history = mutableListOf<ObjectNode>()

    override fun newConversation() = synchronized(history) { history.clear() }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun content(role: String, text: String, signature: String? = null): ObjectNode =
        JSON.createObjectNode().apply {
            put("role", role)
            putArray("parts").addObject().apply {
                put("text", text)
                if (signature != null) put("thoughtSignature", signature)   // echoed back as received
            }
        }

    private fun body(model: String, contents: List<ObjectNode>, withThinking: Boolean): String {
        val root = JSON.createObjectNode()
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt(context()))
        val arr: ArrayNode = root.putArray("contents"); contents.forEach { arr.add(it) }
        val gen = root.putObject("generationConfig")
        gen.put("maxOutputTokens", 1024)
        val level = thinkingLevel(model)
        if (withThinking && level != null) gen.putObject("thinkingConfig").put("thinkingLevel", level)
        return JSON.writeValueAsString(root)
    }

    private class Failure(val status: Int, override val message: String) : Exception(message)

    override fun ask(question: String, events: AnswerEvents): Answer {
        val started = System.currentTimeMillis()
        val live = runCatching { liveData.contextFor(question, homeCity()) }.getOrDefault("")
        val userTurn = content("user", withContext(question, live))
        val contents: List<ObjectNode> = synchronized(history) { history.toList() } + listOf(userTurn)   // ObjectNode is Iterable: wrap it

        var serverRetried = false
        while (true) {
            val model = models[modelIndex]
            try {
                val r = stream(model, contents, events, started)
                if (r.blocked) return Answer("Sorry, I can't help with that one.", ok = false,
                    totalMs = System.currentTimeMillis() - started, detail = "blocked: ${r.finish}")
                if (r.text.isBlank()) return Answer("Sorry, I didn't get an answer. Please try again.", ok = false,
                    totalMs = System.currentTimeMillis() - started, detail = "empty reply from $model (${r.finish})")
                synchronized(history) {
                    history.add(userTurn)
                    history.add(content("model", r.text, r.signature))
                    val trimmed = ClaudeAssistant.trimHistory(history.toList(), MAX_HISTORY_ITEMS) { it.path("role").asText() == "user" }
                    history.clear(); history += trimmed
                }
                return Answer(
                    text = r.text.trim(), ok = true,
                    searches = if (live.isBlank()) emptyList() else listOf(live.lineSequence().first().substringBefore(" (")),
                    firstTextMs = r.firstTextMs, totalMs = System.currentTimeMillis() - started,
                    detail = model,
                )
            } catch (f: Failure) {
                val msg = f.message.orEmpty()
                val lower = msg.lowercase()
                when {
                    f.status == 400 && thinkingAllowed && "thinking" in lower -> { thinkingAllowed = false; continue }
                    (f.status == 404 || "not found" in lower || "is not supported" in lower) && modelIndex < models.lastIndex -> {
                        modelIndex++; thinkingAllowed = true; continue
                    }
                    "free_tier_verification" in lower ->
                        return fail("Google needs you to verify your account before using the free tier. Open AI Studio to finish verification.", started, msg)
                    f.status == 401 || "api key" in lower || "api_key" in lower || (f.status == 403 && "permission" in lower) ->
                        return fail("Your Gemini API key was rejected. Please check it in Settings.", started, msg)
                    f.status == 429 ->
                        return fail("I've hit the free Gemini limit for now. Try again in a minute, or tomorrow if today's limit is used up.", started, msg)
                    f.status >= 500 && !serverRetried -> { serverRetried = true; Thread.sleep(800); continue }
                    f.status >= 500 -> return fail("Gemini is busy right now. Try again in a moment.", started, msg)
                    else -> return fail("Something went wrong with that request.", started, "HTTP ${f.status}: $msg")
                }
            } catch (e: IOException) {
                return fail("I can't reach the internet right now.", started, e.toString())
            } catch (e: Exception) {
                onError(e)
                return fail("Something went wrong. Please try again.", started, e.toString())
            }
        }
    }

    private fun fail(text: String, started: Long, detail: String) =
        Answer(text, ok = false, totalMs = System.currentTimeMillis() - started, detail = detail.take(400))

    private class Streamed(val text: String, val signature: String?, val finish: String, val blocked: Boolean, val firstTextMs: Long)

    private fun stream(model: String, contents: List<ObjectNode>, events: AnswerEvents, started: Long): Streamed {
        val url = "$baseUrl/v1beta/models/$model:streamGenerateContent".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "sse").addQueryParameter("key", apiKey).build()
        val req = Request.Builder().url(url)
            .post(body(model, contents, thinkingAllowed).toRequestBody(JSON_TYPE))
            .build()

        http.newCall(req).execute().use { resp ->
            val source = resp.body?.source() ?: throw IOException("empty response body")
            if (!resp.isSuccessful) throw Failure(resp.code, errorMessage(source.readUtf8()))

            val text = StringBuilder()
            var signature: String? = null
            var finish = ""
            var blocked = false
            var firstText = -1L
            val raw = StringBuilder()
            var sawSse = false

            fun handle(chunk: JsonNode) {
                if (chunk.has("error")) throw Failure(chunk.path("error").path("code").asInt(500), errorMessage(chunk.toString()))
                if (chunk.path("promptFeedback").has("blockReason")) {
                    blocked = true; finish = chunk.path("promptFeedback").path("blockReason").asText()
                }
                val cand = chunk.path("candidates").path(0)
                for (part in cand.path("content").path("parts")) {
                    part.path("thoughtSignature").takeIf { it.isTextual }?.let { signature = it.asText() }
                    if (part.path("thought").asBoolean(false)) continue      // thinking summary, not for speech
                    val t = part.path("text").asText("")
                    if (t.isNotEmpty()) {
                        if (firstText < 0) firstText = System.currentTimeMillis() - started
                        text.append(t)
                        events.onText(t)
                    }
                }
                cand.path("finishReason").takeIf { it.isTextual }?.let {
                    finish = it.asText()
                    if (finish in setOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")) blocked = true
                }
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    sawSse = true
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    handle(JSON.readTree(payload))
                } else if (!sawSse) raw.append(line).append('\n')
            }
            if (!sawSse && raw.isNotBlank()) {
                // Not SSE: a single response object or a JSON array of them.
                val node = JSON.readTree(raw.toString())
                if (node.isArray) node.forEach(::handle) else handle(node)
            }
            return Streamed(text.toString(), signature, finish, blocked && text.isBlank(), firstText)
        }
    }

    /** Best-effort extraction of the error message from Google's error JSON (several shapes). */
    private fun errorMessage(body: String): String = runCatching {
        val n = JSON.readTree(body)
        val e = if (n.isArray) n.path(0).path("error") else n.path("error")
        listOf(e.path("status").asText(""), e.path("code").asText(""), e.path("message").asText(""))
            .filter { it.isNotBlank() }.distinct().joinToString(": ").ifBlank { body }
    }.getOrDefault(body).take(400)
}
