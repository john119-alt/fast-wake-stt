package com.fastwake.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversation history, saved on the phone only (one JSON file per
 * conversation in app storage).
 */
class HistoryStore(context: Context) {

    data class Entry(val role: String, val text: String, val time: Long, val meta: String = "")
    data class Session(val id: Long, val entries: List<Entry>) {
        val title: String get() = entries.firstOrNull { it.role == "user" }?.text ?: "(empty)"
    }

    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private var current: Long = 0

    /** Start a new conversation file; later [add] calls go into it. */
    @Synchronized fun newSession() { current = System.currentTimeMillis() }

    @Synchronized fun add(role: String, text: String, meta: String = "") {
        if (current == 0L) newSession()
        val f = File(dir, "$current.json")
        val obj = if (f.exists()) JSONObject(f.readText()) else JSONObject().put("id", current).put("entries", JSONArray())
        obj.getJSONArray("entries").put(
            JSONObject().put("role", role).put("text", text).put("time", System.currentTimeMillis()).put("meta", meta)
        )
        f.writeText(obj.toString())
    }

    /** All conversations, newest first. */
    fun list(): List<Session> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { parse(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.id }

    fun clear() { dir.listFiles().orEmpty().forEach { it.delete() } }

    private fun parse(o: JSONObject): Session {
        val arr = o.getJSONArray("entries")
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Entry(e.getString("role"), e.getString("text"), e.getLong("time"), e.optString("meta"))
        }
        return Session(o.getLong("id"), entries)
    }
}
