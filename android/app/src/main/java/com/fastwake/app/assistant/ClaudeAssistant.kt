package com.fastwake.app.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where and when the user is, so answers about "today" or "near me" are right. */
data class DeviceContext(val now: ZonedDateTime, val countryCode: String)

/**
 * Talks to Claude directly with the user's own API key (official Java SDK).
 *
 * - Streams the answer so the phone can start speaking after the first sentence.
 * - Gives Claude the web search tool for anything current (weather, news, ...).
 * - Keeps the conversation, including web-search results, for follow-ups.
 * - Opts into server-side refusal fallbacks on the models that support them.
 */
class ClaudeAssistant(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** Override only in tests (local mock server). */
    baseUrl: String? = null,
    /** Receives unexpected errors (for logging); the user still gets a spoken message. */
    private val onError: (Throwable) -> Unit = {},
    private val context: () -> DeviceContext,
) : Assistant {

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            ModelChoice("claude-opus-5-5", "Opus 5.5", "Smartest answers (default)"),
            ModelChoice("claude-sonnet-5-5", "Sonnet 5.5", "Balanced speed and quality"),
            ModelChoice("claude-haiku-5-5", "Haiku 5.5", "Fastest replies, lowest cost"),
        )

        /** Most recent turns kept for context (user + assistant items). */
        const val MAX_HISTORY_ITEMS = 16
        private const val MAX_CONTINUATIONS = 3

        // "fallbacks": "default" re-runs a declined request on the model
        // Anthropic recommends for that refusal category. Claude API only;
        // supported on Opus 5.5 and Sonnet 5.5 (not Haiku).
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5")

        // The dynamic-filtering web search tool needs Opus/Sonnet; Haiku uses
        // the basic version.
        private val BASIC_SEARCH_MODELS = setOf("claude-haiku-5-5")

        fun systemPrompt(ctx: DeviceContext): String {
            val date = ctx.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH))
            val region = if (ctx.countryCode.isNotEmpty()) " Their country code is ${ctx.countryCode}." else ""
            return """
                You are FastWake, a voice assistant running on the user's phone. Everything you write is read aloud by text-to-speech, and the user's words reach you through speech recognition.

                How to answer:
                - Answer in one to three short sentences unless the user asks for more detail. Lead with the answer itself.
                - Write plain spoken English only: no markdown, bullet points, headings, emojis, URLs, or citation markers.
                - Write numbers, times, units, and currencies the way a person would say them out loud.
                - For anything that changes over time (weather, news, sports, prices, exchange rates, opening hours, recent events), use web search and give the result directly. Don't name your sources unless the user asks.
                - Speech recognition sometimes garbles words. If a question doesn't make sense, make your best guess when it's obvious, otherwise ask one short clarifying question.
                - The user can reply without repeating the wake word, so end naturally; don't ask "anything else?" after every answer.

                Context: it is $date (${ctx.now.zone.id}) for the user.$region
            """.trimIndent()
        }

        /**
         * Keep only the newest [max] items, and always start on a user item so
         * the conversation sent to the API stays valid.
         */
        fun <T> trimHistory(items: List<T>, max: Int, isUser: (T) -> Boolean): List<T> {
            if (items.size <= max) return items
            var start = items.size - max
            while (start < items.size && !isUser(items[start])) start++
            return items.subList(start, items.size)
        }
    }

    data class ModelChoice(val id: String, val label: String, val blurb: String)

    /** Conversation history: user questions as text, Claude's replies as full messages. */
    private sealed class Item {
        data class User(val text: String) : Item()
        data class Reply(val message: Message) : Item()
    }

    private val history = mutableListOf<Item>()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override fun newConversation() = synchronized(history) { history.clear() }

    override fun close() = client.close()

    private fun buildParams(items: List<Item>): MessageCreateParams {
        val ctx = context()
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(systemPrompt(ctx))
            // Voice answers should be quick: low effort keeps thinking short.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())

        val location = UserLocation.builder().timezone(ctx.now.zone.id).apply {
            if (ctx.countryCode.length == 2) country(ctx.countryCode)
        }.build()
        if (model in BASIC_SEARCH_MODELS) {
            b.addTool(WebSearchTool20250305.builder().maxUses(3L).userLocation(location).build())
        } else {
            b.addTool(WebSearchTool20260209.builder().maxUses(3L).userLocation(location).build())
        }

        if (model in FALLBACK_MODELS) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        for (item in items) when (item) {
            is Item.User -> b.addUserMessage(item.text)
            is Item.Reply -> b.addMessage(item.message)   // keeps web-search blocks for follow-ups
        }
        return b.build()
    }

    override fun ask(question: String, events: AnswerEvents): Answer {
        val started = System.currentTimeMillis()
        var firstText = -1L
        val spoken = StringBuilder()
        val turn = mutableListOf<Item>(Item.User(question))
        val base = synchronized(history) { history.toList() }

        try {
            var continuations = 0
            while (true) {
                val acc = MessageAccumulator.create()
                client.messages().createStreaming(buildParams(base + turn)).use { response ->
                    response.stream().forEach { event ->
                        acc.accumulate(event)
                        event.contentBlockStart().ifPresent { start ->
                            if (start.contentBlock().serverToolUse().isPresent) events.onSearching()
                        }
                        event.contentBlockDelta().ifPresent { d ->
                            d.delta().text().ifPresent { t ->
                                if (firstText < 0) firstText = System.currentTimeMillis() - started
                                spoken.append(t.text())
                                events.onText(t.text())
                            }
                        }
                    }
                }
                val message = acc.message()
                when (message.stopReason().orElse(null)) {
                    StopReason.PAUSE_TURN -> {
                        // The server paused a long web-search turn; send it back to resume.
                        turn += Item.Reply(message)
                        if (++continuations > MAX_CONTINUATIONS) break
                    }
                    StopReason.REFUSAL -> {
                        // Not added to history: a declined turn shouldn't shape follow-ups.
                        return Answer("Sorry, I can't help with that one.", ok = false,
                            totalMs = System.currentTimeMillis() - started)
                    }
                    else -> {
                        turn += Item.Reply(message)
                        break
                    }
                }
            }
        } catch (e: UnauthorizedException) {
            return fail("Your Claude API key was rejected. Please check it in Settings.", started)
        } catch (e: PermissionDeniedException) {
            return fail("Your Claude API key isn't allowed to use this model. Try another model in Settings.", started)
        } catch (e: RateLimitException) {
            return fail("I'm getting too many requests right now. Try again in a moment.", started)
        } catch (e: BadRequestException) {
            val credit = e.message.orEmpty().contains("credit", ignoreCase = true)
            return fail(if (credit) "Your Claude account is out of credit." else "Something went wrong with that request.", started)
        } catch (e: AnthropicServiceException) {
            return fail("Claude is busy right now. Try again in a moment.", started)
        } catch (e: AnthropicIoException) {
            return fail("I can't reach the internet right now.", started)
        } catch (e: Exception) {
            onError(e)
            return fail("Something went wrong. Please try again.", started)
        }

        synchronized(history) {
            history += turn
            val trimmed = trimHistory(history.toList(), MAX_HISTORY_ITEMS) { it is Item.User }
            history.clear(); history += trimmed
        }
        return Answer(
            text = spoken.toString().trim(),
            ok = true,
            searches = turn.filterIsInstance<Item.Reply>().flatMap { searchQueries(it.message) },
            firstTextMs = firstText,
            totalMs = System.currentTimeMillis() - started,
        )
    }

    private fun fail(text: String, started: Long) =
        Answer(text, ok = false, totalMs = System.currentTimeMillis() - started)

    private fun searchQueries(message: Message): List<String> =
        message.content().mapNotNull { block ->
            block.serverToolUse().orElse(null)?.let { use ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (use._input().convert(Map::class.java) as Map<String, Any?>)["query"] as? String
                }.getOrNull()
            }
        }
}
