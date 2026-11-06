package com.fastwake.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line in the on-screen conversation. */
data class Turn(val fromUser: Boolean, val text: String, val meta: String = "")

/**
 * Live state shared between the background service and the screen. The
 * service writes; the UI observes.
 */
object AssistantState {
    private val _running = MutableStateFlow(false)
    private val _phase = MutableStateFlow(Phase.LISTENING)
    private val _status = MutableStateFlow("")
    private val _hearing = MutableStateFlow(false)
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    private val _streaming = MutableStateFlow("")

    val running: StateFlow<Boolean> = _running.asStateFlow()
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    /** Extra detail: download progress, "Searching the web…", errors. */
    val status: StateFlow<String> = _status.asStateFlow()
    /** Voice detector currently hears speech. */
    val hearing: StateFlow<Boolean> = _hearing.asStateFlow()
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()
    /** The answer as it streams in. */
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    internal fun setRunning(v: Boolean) { _running.value = v }
    internal fun setPhase(p: Phase) { _phase.value = p }
    internal fun setStatus(s: String) { _status.value = s }
    internal fun setHearing(v: Boolean) { _hearing.value = v }
    internal fun appendStreaming(s: String) { _streaming.value += s }
    internal fun clearStreaming() { _streaming.value = "" }
    internal fun addTurn(t: Turn) { _turns.value = (_turns.value + t).takeLast(40) }
    internal fun clearTurns() { _turns.value = emptyList() }
}
