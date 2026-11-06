package com.fastwake.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.fastwake.app.assistant.AnswerEvents
import com.fastwake.app.assistant.ClaudeAssistant
import com.fastwake.app.assistant.DeviceContext
import com.fastwake.app.data.Prefs
import com.fastwake.app.data.SecureStore
import com.fastwake.app.service.AssistantService
import com.fastwake.app.service.AssistantState
import com.fastwake.app.ui.Ui.card
import com.fastwake.app.ui.Ui.dp
import com.fastwake.app.ui.Ui.headerRow
import com.fastwake.app.ui.Ui.lp
import com.fastwake.app.ui.Ui.primaryButton
import com.fastwake.app.ui.Ui.rounded
import com.fastwake.app.ui.Ui.screen
import com.fastwake.app.ui.Ui.sectionTitle
import com.fastwake.app.ui.Ui.text
import com.fastwake.app.ui.Ui.textButton
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.concurrent.thread

class SettingsActivity : Activity() {

    private lateinit var secure: SecureStore
    private lateinit var prefs: Prefs
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var testResult: TextView
    private val main = Handler(Looper.getMainLooper())
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        secure = SecureStore(this); prefs = Prefs(this)

        val root = screen()
        root.addView(headerRow("Settings", textButton("Done") { finish() }))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- API key ---
        body.addView(sectionTitle("Claude API key"))
        val keyCard = card()
        keyStatus = text("", 14f, Ui.MUTED)
        keyCard.addView(keyStatus)
        keyInput = EditText(this).apply {
            hint = "sk-ant-…"; setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = rounded(Ui.SURFACE_2, dp(12).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        keyCard.addView(keyInput, lp(top = dp(10)))
        val keyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyButtons.addView(textButton("Save key") { saveKey() })
        keyButtons.addView(textButton("Test key") { testKey() })
        keyButtons.addView(textButton("Remove", Ui.BAD) { secure.apiKey = null; changed = true; refreshKey() })
        keyCard.addView(keyButtons, lp(top = dp(6)))
        testResult = text("", 13f, Ui.MUTED).apply { visibility = View.GONE }
        keyCard.addView(testResult)
        keyCard.addView(text(
            "Create a key at console.anthropic.com → API Keys. It's stored encrypted on this phone " +
                "(Android Keystore) and sent only to Anthropic. Usage is billed to your Anthropic account.",
            12f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        keyCard.addView(textButton("Open console.anthropic.com") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        })
        body.addView(keyCard)

        // --- model ---
        body.addView(sectionTitle("Model"))
        val modelCard = card()
        val group = RadioGroup(this)
        ClaudeAssistant.MODELS.forEachIndexed { i, m ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "${m.label} — ${m.blurb}"; setTextColor(Ui.TEXT); textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                isChecked = prefs.model == m.id
                setOnCheckedChangeListener { _, checked -> if (checked) { prefs.model = m.id; changed = true } }
                setPadding(dp(4), dp(6), 0, dp(6))
            })
        }
        modelCard.addView(group)
        body.addView(modelCard)

        // --- behaviour ---
        body.addView(sectionTitle("Listening"))
        val behaviour = card()
        behaviour.addView(switch("Follow-up questions",
            "After an answer, keep listening for 7 seconds so you can reply without saying “Hey Ordinary” again.",
            prefs.followUp) { prefs.followUp = it })
        behaviour.addView(switch("Wake chime", "Play a short tone when it hears “Hey Ordinary” on its own.",
            prefs.chime) { prefs.chime = it })
        behaviour.addView(text("Speaking speed", 15f).apply { setPadding(0, dp(12), 0, 0) })
        val rateLabel = text("", 12f, Ui.MUTED)
        val seek = SeekBar(this).apply {
            max = 80   // 0.7x .. 1.5x
            progress = ((prefs.speechRate - 0.7f) * 100).toInt().coerceIn(0, 80)
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val r = 0.7f + p / 100f
                    rateLabel.text = "%.1f×".format(r)
                    if (fromUser) prefs.speechRate = r
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        rateLabel.text = "%.1f×".format(prefs.speechRate)
        behaviour.addView(seek, lp(top = dp(4)))
        behaviour.addView(rateLabel)
        body.addView(behaviour)

        body.addView(text(
            "Wake word detection and speech-to-text run on the phone. Only your question " +
                "(after “Hey Ordinary”) is sent to Claude.",
            12f, Ui.MUTED).apply { setPadding(dp(4), dp(16), dp(4), dp(24)) })

        setContentView(root)
        refreshKey()
    }

    override fun onPause() {
        super.onPause()
        // Key and model are read when listening starts: restart if they changed.
        if (changed && AssistantState.running.value) {
            changed = false
            AssistantService.stop(this)
            main.postDelayed({ AssistantService.start(applicationContext) }, 1200)
        }
    }

    private fun switch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
            addView(Switch(this@SettingsActivity).apply {
                text = title; setTextColor(Ui.TEXT); textSize = 15f; isChecked = value
                setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
            })
            addView(text(detail, 12f, Ui.MUTED))
        }

    private fun refreshKey() {
        val key = secure.apiKey
        keyStatus.text = if (key.isNullOrBlank()) "No key saved." else "Saved key ending in …${key.takeLast(4)}"
        keyStatus.setTextColor(if (key.isNullOrBlank()) Ui.WARN else Ui.GOOD)
    }

    private fun saveKey() {
        val k = keyInput.text.toString().trim()
        if (k.isEmpty()) return
        secure.apiKey = k
        keyInput.setText("")
        changed = true
        refreshKey()
        showTest("Saved. Tap Test key to check it.", Ui.MUTED)
    }

    /** One tiny real request, so a typo'd key is caught here rather than mid-conversation. */
    private fun testKey() {
        val key = keyInput.text.toString().trim().ifEmpty { secure.apiKey }
        if (key.isNullOrBlank()) { showTest("Enter or save a key first.", Ui.WARN); return }
        showTest("Testing with ${prefs.model}…", Ui.MUTED)
        val model = prefs.model
        thread(name = "key-test") {
            val result = ClaudeAssistant(apiKey = key, model = model) {
                DeviceContext(ZonedDateTime.now(), Locale.getDefault().country)
            }.use { it.ask("Reply with just the word OK.", object : AnswerEvents {}) }
            main.post {
                if (result.ok) showTest("Key works. Claude replied “${result.text.take(40)}” in ${result.totalMs} ms.", Ui.GOOD)
                else showTest(result.text, Ui.BAD)
            }
        }
    }

    private fun showTest(s: String, color: Int) {
        testResult.visibility = View.VISIBLE
        testResult.text = s
        testResult.setTextColor(color)
        testResult.setPadding(0, dp(6), 0, 0)
    }
}
