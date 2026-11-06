package com.fastwake.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fastwake.app.data.Prefs
import com.fastwake.app.data.SecureStore
import com.fastwake.app.service.AssistantService
import com.fastwake.app.service.AssistantState
import com.fastwake.app.service.Phase
import com.fastwake.app.service.Turn
import com.fastwake.app.ui.Ui.card
import com.fastwake.app.ui.Ui.dp
import com.fastwake.app.ui.Ui.headerRow
import com.fastwake.app.ui.Ui.lp
import com.fastwake.app.ui.Ui.primaryButton
import com.fastwake.app.ui.Ui.rounded
import com.fastwake.app.ui.Ui.screen
import com.fastwake.app.ui.Ui.text
import com.fastwake.app.ui.Ui.textButton
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Main screen: status orb, live conversation, start/stop, typed questions. */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var orb: View
    private lateinit var phaseLabel: TextView
    private lateinit var statusLine: TextView
    private lateinit var keyBanner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerBody: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var streamingBubble: TextView
    private lateinit var mainButton: android.widget.Button
    private lateinit var stopTalking: android.widget.Button
    private lateinit var input: EditText
    private var pulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = screen()
        root.addView(headerRow("FastWake",
            textButton("History") { startActivity(Intent(this, HistoryActivity::class.java)) },
            textButton("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ))

        keyBanner = card().apply {
            background = rounded(Color.parseColor("#2A2412"), dp(16).toFloat(), dp(1), Ui.WARN)
            bannerTitle = text("", 15f, Ui.WARN, bold = true).also(::addView)
            bannerBody = text("", 13f, Ui.MUTED).also(::addView)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(keyBanner, lp(top = dp(12)))

        // Status orb + labels
        val orbBox = FrameLayout(this)
        orb = View(this).apply { background = rounded(Ui.SURFACE_2, dp(60).toFloat()) }
        orbBox.addView(orb, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        root.addView(orbBox, lp(h = dp(150), top = dp(8)))
        phaseLabel = text("", 20f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        statusLine = text("", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER; minLines = 1 }
        root.addView(phaseLabel, lp())
        root.addView(statusLine, lp(top = dp(4)))

        // Conversation
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        streamingBubble = bubble("", fromUser = false).apply { visibility = View.GONE }
        scroll = ScrollView(this).apply { addView(conversation); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        // Typed question
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.SURFACE, dp(24).toFloat())
            setPadding(dp(16), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "Type a question…"; setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
            background = null; maxLines = 3; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(textButton("Send") { sendTyped() })
        root.addView(inputRow, lp(top = dp(8)))

        stopTalking = primaryButton("Stop answer", Ui.SURFACE_2) { AssistantService.cancel(this) }
        root.addView(stopTalking, lp(top = dp(10)))
        mainButton = primaryButton("Start listening") { toggle() }
        root.addView(mainButton, lp(top = dp(10)))

        setContentView(root)
        observe()
    }

    override fun onResume() {
        super.onResume()
        val provider = Prefs(this).provider
        keyBanner.visibility = if (SecureStore(this).hasKey(provider)) View.GONE else View.VISIBLE
        if (provider == Prefs.PROVIDER_CLAUDE) {
            bannerTitle.text = "Add your Claude API key"
            bannerBody.text = "Answers need a key from console.anthropic.com. Tap to open Settings."
        } else {
            bannerTitle.text = "Add a free Gemini API key"
            bannerBody.text = "Get one at aistudio.google.com (no card needed), then paste it in Settings. Tap here."
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // --- state -> views ---------------------------------------------------------

    private fun observe() {
        scope.launch {
            combine(AssistantState.running, AssistantState.phase, AssistantState.hearing) { r, p, h -> Triple(r, p, h) }
                .collect { (running, phase, hearing) -> render(running, phase, hearing) }
        }
        scope.launch { AssistantState.status.collect { statusLine.text = it } }
        scope.launch { AssistantState.turns.collect { renderTurns(it) } }
        scope.launch {
            AssistantState.streaming.collect { s ->
                streamingBubble.text = s
                streamingBubble.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
                scrollToEnd()
            }
        }
    }

    private fun render(running: Boolean, phase: Phase, hearing: Boolean) {
        mainButton.text = if (running) "Stop listening" else "Start listening"
        mainButton.background = rounded(if (running) Ui.SURFACE_2 else Ui.ACCENT_DARK, dp(28).toFloat())
        stopTalking.visibility = if (running && (phase == Phase.THINKING || phase == Phase.SPEAKING)) View.VISIBLE else View.GONE

        val (label, color) = when {
            !running -> "Off" to Ui.SURFACE_2
            phase == Phase.THINKING -> "Thinking…" to Ui.WARN
            phase == Phase.SPEAKING -> "Speaking" to Ui.GOOD
            phase == Phase.AWAITING_QUESTION -> "Go ahead, I'm listening" to Ui.PURPLE
            phase == Phase.FOLLOW_UP -> "Ask a follow-up" to Ui.PURPLE
            hearing -> "Hearing speech…" to Ui.ACCENT
            else -> "Say “Hey Ordinary”" to Ui.ACCENT_DARK
        }
        phaseLabel.text = label
        orb.background = rounded(color, dp(60).toFloat())
        val animate = running && (hearing || phase == Phase.THINKING || phase == Phase.SPEAKING)
        if (animate && pulse == null) {
            pulse = ObjectAnimator.ofFloat(orb, View.SCALE_X, 1f, 1.12f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { orb.scaleY = orb.scaleX }
                start()
            }
        } else if (!animate) {
            pulse?.cancel(); pulse = null; orb.scaleX = 1f; orb.scaleY = 1f
        }
    }

    private fun renderTurns(turns: List<Turn>) {
        conversation.removeAllViews()
        if (turns.isEmpty()) {
            conversation.addView(text(
                "Try: “Hey Ordinary, what's the weather in Mumbai today?”\n" +
                    "After an answer, ask a follow-up without the wake word.",
                14f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(24), dp(12), 0) })
        }
        for (t in turns) {
            conversation.addView(bubble(t.text, t.fromUser), bubbleLp(t.fromUser))
            if (t.meta.isNotEmpty() && t.meta != "error") {   // "error · <detail>" is shown
                conversation.addView(text(t.meta, 11f, Ui.MUTED).apply { setPadding(dp(6), dp(2), 0, dp(4)) })
            }
        }
        (streamingBubble.parent as? LinearLayout)?.removeView(streamingBubble)
        conversation.addView(streamingBubble, bubbleLp(false))
        scrollToEnd()
    }

    private fun bubble(s: String, fromUser: Boolean): TextView = text(s, 15f, Ui.TEXT).apply {
        background = rounded(if (fromUser) Ui.ACCENT_DARK else Ui.SURFACE, dp(18).toFloat())
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextIsSelectable(true)
    }

    private fun bubbleLp(fromUser: Boolean) = LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = dp(8)
        gravity = if (fromUser) Gravity.END else Gravity.START
        if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    // --- actions -------------------------------------------------------------------

    private fun toggle() {
        if (AssistantState.running.value) { AssistantService.stop(this); return }
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1) else AssistantService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        val mic = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (mic >= 0 && results.getOrNull(mic) != PackageManager.PERMISSION_GRANTED) {
            statusLine.text = "Microphone permission is needed to listen for “Hey Ordinary”."
            return
        }
        AssistantService.start(this)   // notifications are optional
    }

    private fun sendTyped() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        if (!AssistantState.running.value) {
            statusLine.text = "Press Start listening first."
            return
        }
        AssistantService.ask(this, q)
    }
}
