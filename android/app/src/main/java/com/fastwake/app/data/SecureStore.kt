package com.fastwake.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API keys (Claude, Gemini) encrypted with an AES-GCM key that lives in the
 * Android Keystore (hardware-backed on most phones), so the key never sits in
 * plain text on disk and can't be read by other apps.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_ALIAS = "fastwake.apikey"
        const val PREF = "api_key"
        const val PREF_GEMINI = "gemini_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun read(pref: String): String? {
        val stored = prefs.getString(pref, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun write(pref: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(pref).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(pref, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /** Claude (Anthropic) API key. */
    var apiKey: String?
        get() = read(PREF)
        set(value) = write(PREF, value)

    /** Google Gemini (AI Studio) API key. */
    var geminiKey: String?
        get() = read(PREF_GEMINI)
        set(value) = write(PREF_GEMINI, value)

    fun key(provider: String): String? = if (provider == Prefs.PROVIDER_CLAUDE) apiKey else geminiKey
    fun setKey(provider: String, value: String?) { if (provider == Prefs.PROVIDER_CLAUDE) apiKey = value else geminiKey = value }
    fun hasKey(provider: String): Boolean = !key(provider).isNullOrBlank()

    val hasApiKey: Boolean get() = !apiKey.isNullOrBlank()
}
