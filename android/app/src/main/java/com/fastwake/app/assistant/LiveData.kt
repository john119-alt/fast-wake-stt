package com.fastwake.app.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Free, key-less live data the app fetches itself and hands to the model as
 * context, so a free model with no web access can still answer "what's the
 * weather in Mumbai" or "who is Virat Kohli".
 *
 * Doing this before the model call (instead of letting the model call tools)
 * keeps it to a single model round trip, which is faster for voice.
 *
 * Sources: Open-Meteo (weather + geocoding) and Wikipedia. No keys, no billing.
 */
open class LiveData(
    private val http: OkHttpClient = defaultClient(),
    private val geocodeBase: String = "https://geocoding-api.open-meteo.com",
    private val forecastBase: String = "https://api.open-meteo.com",
    private val wikiBase: String = "https://en.wikipedia.org",
) {
    companion object {
        private val json = ObjectMapper()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()

        private val WEATHER_WORDS = Regex(
            """\b(weather|temperature|forecast|rain(ing|y)?|umbrella|humid(ity)?|hot|cold|sunny|cloudy|wind(y)?|storm|snow(ing)?|degrees)\b""",
            RegexOption.IGNORE_CASE)
        // "weather in Mumbai today", "will it rain in New Delhi tomorrow?", "temperature at Pune"
        private val PLACE_AFTER = Regex(
            """\b(?:in|at|for|of)\s+([A-Za-z][A-Za-z .'-]{1,40}?)(?=\s+(?:today|tomorrow|tonight|this|next|now|right|on|at|in|over|during|for|later)\b|[?.!,]|$)""",
            RegexOption.IGNORE_CASE)
        private val NOT_PLACES = setOf("the", "my", "here", "there", "it", "this", "that", "me", "you", "us",
            "today", "tomorrow", "tonight", "the morning", "the evening", "the afternoon", "the weekend", "general")

        private val LOOKUP = Regex(
            """^\s*(?:who|what)\s+(?:is|was|are|were)\s+(.+?)[?.!]*\s*$|^\s*(?:tell me about|who's|what's)\s+(.+?)[?.!]*\s*$""",
            RegexOption.IGNORE_CASE)
        // Questions that start like a lookup but aren't encyclopedia questions.
        private val NOT_LOOKUP = Regex(
            """^(the )?(time|date|day|weather|temperature|forecast|your name|up|new|happening|going on|the matter|wrong|it|that|this|my|\d)""",
            RegexOption.IGNORE_CASE)

        /** The place named in a weather question, or null if none. Pure, unit-tested. */
        fun placeIn(question: String): String? {
            val m = PLACE_AFTER.findAll(question).lastOrNull() ?: return null
            val place = m.groupValues[1].trim().trimEnd('.', ',')
            return place.takeUnless { it.lowercase() in NOT_PLACES || it.length < 2 }
        }

        fun isWeather(question: String) = WEATHER_WORDS.containsMatchIn(question)

        /** Subject of an encyclopedia-style question ("who is Virat Kohli" -> "Virat Kohli"), or null. */
        fun lookupSubject(question: String): String? {
            val m = LOOKUP.find(question) ?: return null
            val subject = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
                .removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
            if (subject.length < 2 || NOT_LOOKUP.containsMatchIn(subject) || isWeather(subject)) return null
            return subject
        }

        /** WMO weather code -> words (https://open-meteo.com/en/docs). */
        fun describe(code: Int): String = when (code) {
            0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
            45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
            61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
            71 -> "light snow"; 73 -> "snow"; 75 -> "heavy snow"; 77 -> "snow grains"
            80 -> "light showers"; 81 -> "showers"; 82 -> "violent showers"
            85, 86 -> "snow showers"; 95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
            else -> "unknown conditions"
        }
    }

    /**
     * Facts relevant to [question], formatted for the model, or "" if none.
     * Never throws: anything that fails or times out is simply left out.
     */
    open fun contextFor(question: String, homeCity: String): String {
        val parts = mutableListOf<String>()
        if (isWeather(question)) {
            val place = placeIn(question) ?: homeCity.takeIf { it.isNotBlank() }
            if (place != null) runCatching { weather(place) }.getOrNull()?.let { parts += it }
            else parts += "The user didn't name a place and no home city is set; ask which city they mean."
        }
        lookupSubject(question)?.let { subject ->
            runCatching { wikipedia(subject) }.getOrNull()?.let { parts += it }
        }
        return parts.joinToString("\n\n")
    }

    private fun get(url: HttpUrl): JsonNode? {
        val req = Request.Builder().url(url).header("User-Agent", "FastWake/0.4 (Android voice assistant)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return json.readTree(r.body?.string() ?: return null)
        }
    }

    fun weather(place: String): String? {
        val geo = get("$geocodeBase/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", place).addQueryParameter("count", "1")
            .addQueryParameter("language", "en").addQueryParameter("format", "json").build())
            ?.path("results")?.get(0) ?: return null
        val lat = geo.path("latitude").asDouble(); val lon = geo.path("longitude").asDouble()
        val where = listOf(geo.path("name").asText(), geo.path("admin1").asText(), geo.path("country").asText())
            .filter { it.isNotBlank() }.distinct().joinToString(", ")

        val f = get("$forecastBase/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "$lat").addQueryParameter("longitude", "$lon")
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "3").build()) ?: return null

        val c = f.path("current")
        val sb = StringBuilder("Live weather for $where (Open-Meteo, local time ${c.path("time").asText()}):\n")
        sb.append("Now: ${c.path("temperature_2m").asDouble()}°C, feels like ${c.path("apparent_temperature").asDouble()}°C, ")
            .append("${describe(c.path("weather_code").asInt())}, humidity ${c.path("relative_humidity_2m").asInt()}%, ")
            .append("wind ${c.path("wind_speed_10m").asDouble()} km/h, precipitation ${c.path("precipitation").asDouble()} mm.\n")
        val d = f.path("daily")
        val labels = listOf("Today", "Tomorrow", "Day after")
        for (i in 0 until minOf(3, d.path("time").size())) {
            sb.append("${labels[i]} (${d.path("time").get(i).asText()}): ")
                .append("${describe(d.path("weather_code").get(i).asInt())}, ")
                .append("high ${d.path("temperature_2m_max").get(i).asDouble()}°C, low ${d.path("temperature_2m_min").get(i).asDouble()}°C, ")
                .append("chance of rain ${d.path("precipitation_probability_max").get(i).asInt()}%.\n")
        }
        return sb.toString().trim()
    }

    fun wikipedia(subject: String): String? {
        val hit = get("$wikiBase/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search")
            .addQueryParameter("srsearch", subject).addQueryParameter("srlimit", "1")
            .addQueryParameter("format", "json").build())
            ?.path("query")?.path("search")?.get(0)?.path("title")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val summary = get("$wikiBase/api/rest_v1/page/summary/".toHttpUrl().newBuilder()
            .addPathSegment(hit.replace(' ', '_')).build()) ?: return null
        val extract = summary.path("extract").asText().takeIf { it.isNotBlank() } ?: return null
        return "Wikipedia summary of \"$hit\" (may be out of date for very recent events):\n${extract.take(1200)}"
    }
}
