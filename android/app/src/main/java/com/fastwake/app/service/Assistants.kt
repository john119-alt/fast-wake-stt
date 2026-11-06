package com.fastwake.app.service

import android.content.Context
import android.util.Log
import com.fastwake.app.assistant.Assistant
import com.fastwake.app.assistant.ClaudeAssistant
import com.fastwake.app.assistant.DeviceContext
import com.fastwake.app.assistant.GeminiAssistant
import com.fastwake.app.data.Prefs
import com.fastwake.app.data.SecureStore
import java.time.ZonedDateTime
import java.util.Locale

/** Builds the assistant chosen in Settings, or null if that provider has no key yet. */
object Assistants {
    private const val TAG = "FastWake"

    fun deviceContext() = DeviceContext(ZonedDateTime.now(), Locale.getDefault().country)

    fun create(context: Context, prefs: Prefs = Prefs(context), keyOverride: String? = null): Assistant? {
        val provider = prefs.provider
        val key = keyOverride?.takeIf { it.isNotBlank() } ?: SecureStore(context).key(provider) ?: return null
        val log: (Throwable) -> Unit = { Log.e(TAG, "assistant error", it) }
        return when (provider) {
            Prefs.PROVIDER_CLAUDE -> ClaudeAssistant(apiKey = key, model = prefs.model, onError = log) { deviceContext() }
            else -> GeminiAssistant(apiKey = key, speed = prefs.geminiSpeed, homeCity = { prefs.homeCity }, onError = log) { deviceContext() }
        }
    }

    fun providerName(prefs: Prefs) = if (prefs.provider == Prefs.PROVIDER_CLAUDE) "Claude" else "Gemini"
}
