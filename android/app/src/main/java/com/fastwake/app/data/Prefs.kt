package com.fastwake.app.data

import android.content.Context
import com.fastwake.app.assistant.ClaudeAssistant
import com.fastwake.app.assistant.GeminiAssistant

/** User settings (non-secret). API keys live in [SecureStore]. */
class Prefs(context: Context) {
    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_CLAUDE = "claude"
    }

    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Which AI answers: Gemini (free tier, default) or Claude (paid). */
    var provider: String
        get() = p.getString("provider", PROVIDER_GEMINI)!!
        set(v) = p.edit().putString("provider", v).apply()

    /** Gemini speed: GeminiAssistant.SPEED_FAST or SPEED_SMART. */
    var geminiSpeed: String
        get() = p.getString("gemini_speed", GeminiAssistant.SPEED_FAST)!!
        set(v) = p.edit().putString("gemini_speed", v).apply()

    /** Used for weather questions that don't name a place. */
    var homeCity: String
        get() = p.getString("home_city", "")!!
        set(v) = p.edit().putString("home_city", v.trim()).apply()

    /** Claude model id. */
    var model: String
        get() = p.getString("model", ClaudeAssistant.DEFAULT_MODEL)!!
        set(v) = p.edit().putString("model", v).apply()

    /** After an answer, listen briefly for a follow-up without the wake word. */
    var followUp: Boolean
        get() = p.getBoolean("follow_up", true)
        set(v) = p.edit().putBoolean("follow_up", v).apply()

    var speechRate: Float
        get() = p.getFloat("speech_rate", 1.0f)
        set(v) = p.edit().putFloat("speech_rate", v).apply()

    /** Play a short tone when the wake word is heard. */
    var chime: Boolean
        get() = p.getBoolean("chime", true)
        set(v) = p.edit().putBoolean("chime", v).apply()
}
