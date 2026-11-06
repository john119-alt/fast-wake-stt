package com.fastwake.app.data

import android.content.Context
import com.fastwake.app.assistant.ClaudeAssistant

/** User settings (non-secret). The API key lives in [SecureStore]. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

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
