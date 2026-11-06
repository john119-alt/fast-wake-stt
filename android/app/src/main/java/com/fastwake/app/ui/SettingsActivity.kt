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
import com.fastwake.app.assistant.GeminiAssistant
import com.fastwake.app.service.Assistants
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
import kotlin.concurrent.thread

class SettingsActivity : Activity() {

    private lateinit var secure: SecureStore
    private lateinit var prefs: Prefs
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var testResult: TextView
    private lateinit var keyTitle: TextView
    private lateinit var keyHelp: TextView
    private lateinit var keyLink: android.widget.Button
    private lateinit var geminiModels: RadioGroup
    private lateinit var claudeModels: RadioGroup
    private lateinit var homeCityInput: EditText
    private var keyUrl = ""
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

        // --- AI provider ---
        body.addView(sectionTitle("AI provider"))
        val providerCard = card()
        val providers = RadioGroup(this)
        listOf(
            Prefs.PROVIDER_GEMINI to "Google Gemini — free (no card needed)",
            Prefs.PROVIDER_CLAUDE to "Anthropic Claude — paid, with live web search",
        ).forEach { (id, label) ->
            providers.addView(radio(label, prefs.provider == id) { prefs.provider = id; changed = true; refreshProvider() })
        }
        providerCard.addView(providers)
        body.addView(providerCard)

        // --- API key (for the chosen provider) ---
        keyTitle = sectionTitle("")
        body.addView(keyTitle)
        val keyCard = card()
        keyStatus = text("", 14f, Ui.MUTED)
        keyCard.addView(keyStatus)
        keyInput = EditText(this).apply {
            setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = rounded(Ui.SURFACE_2, dp(12).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        keyCard.addView(keyInput, lp(top = dp(10)))
        val keyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyButtons.addView(textButton("Save key") { saveKey() })
        keyButtons.addView(textButton("Test key") { testKey() })
        keyButtons.addView(textButton("Remove", Ui.BAD) { secure.setKey(prefs.provider, null); changed = true; refreshProvider() })
        keyCard.addView(keyButtons, lp(top = dp(6)))
        testResult = text("", 13f, Ui.MUTED).apply { visibility = View.GONE; setTextIsSelectable(true) }
        keyCard.addView(testResult)
        keyHelp = text("", 12f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }
        keyCard.addView(keyHelp)
        keyLink = textButton("") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))) }
        keyCard.addView(keyLink)
        body.addView(keyCard)

        // --- model (for the chosen provider) ---
        body.addView(sectionTitle("Model"))
        val modelCard = card()
        geminiModels = RadioGroup(this).apply {
            listOf(
                GeminiAssistant.SPEED_FAST to "Fast — Gemini Flash-Lite, quickest replies",
                GeminiAssistant.SPEED_SMART to "Smart — Gemini Flash, better answers, a bit slower",
            ).forEach { (id, label) -> addView(radio(label, prefs.geminiSpeed == id) { prefs.geminiSpeed = id; changed = true }) }
        }
        claudeModels = RadioGroup(this).apply {
            ClaudeAssistant.MODELS.forEach { m ->
                addView(radio("${m.label} — ${m.blurb}", prefs.model == m.id) { prefs.model = m.id; changed = true })
            }
        }
        modelCard.addView(geminiModels)
        modelCard.addView(claudeModels)
        body.addView(modelCard)

        // --- home city (weather) ---
        body.addView(sectionTitle("Home city"))
        val cityCard = card()
        val cityInput = EditText(this).apply {
            hint = "e.g. Mumbai"; setText(prefs.homeCity); setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = rounded(Ui.SURFACE_2, dp(12).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { _, focused -> if (!focused) prefs.homeCity = text.toString() }
        }
        homeCityInput = cityInput
        cityCard.addView(cityInput)
        cityCard.addView(text("Used when you ask about the weather without naming a place.", 12f, Ui.MUTED)
            .apply { setPadding(0, dp(8), 0, 0) })
        body.addView(cityCard)

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
        refreshProvider()
    }

    override fun onPause() {
        super.onPause()
        if (homeCityInput.text.toString().trim() != prefs.homeCity) { prefs.homeCity = homeCityInput.text.toString(); changed = true }
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

    private fun radio(label: String, checked: Boolean, onPick: () -> Unit) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label; setTextColor(Ui.TEXT); textSize = 15f
        buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        isChecked = checked
        setOnCheckedChangeListener { _, on -> if (on) onPick() }
        setPadding(dp(4), dp(6), 0, dp(6))
    }

    private val isClaude get() = prefs.provider == Prefs.PROVIDER_CLAUDE

    /** Show the key, help text and models for the chosen provider. */
    private fun refreshProvider() {
        val name = Assistants.providerName(prefs)
        keyTitle.text = "$name API key".uppercase()
        val key = secure.key(prefs.provider)
        keyStatus.text = if (key.isNullOrBlank()) "No $name key saved." else "Saved $name key ending in …${key.takeLast(4)}"
        keyStatus.setTextColor(if (key.isNullOrBlank()) Ui.WARN else Ui.GOOD)
        keyInput.hint = if (isClaude) "sk-ant-…" else "Paste your Gemini key"
        if (isClaude) {
            keyHelp.text = "Create a key at console.anthropic.com → API Keys. Usage is billed to your Anthropic account. " +
                "Stored encrypted on this phone (Android Keystore) and sent only to Anthropic."
            keyLink.text = "Open console.anthropic.com"
            keyUrl = "https://console.anthropic.com/settings/keys"
        } else {
            keyHelp.text = "Free: sign in at aistudio.google.com with your Google account, tap Get API key, then " +
                "Create API key. No card needed. The free tier has daily limits, and Google may use free-tier " +
                "conversations to improve its products. Weather and Wikipedia facts are fetched by the app; live news " +
                "and sports scores need Claude. Stored encrypted on this phone and sent only to Google."
            keyLink.text = "Open aistudio.google.com"
            keyUrl = "https://aistudio.google.com/apikey"
        }
        geminiModels.visibility = if (isClaude) View.GONE else View.VISIBLE
        claudeModels.visibility = if (isClaude) View.VISIBLE else View.GONE
        testResult.visibility = View.GONE
    }

    private fun saveKey() {
        val k = keyInput.text.toString().trim()
        if (k.isEmpty()) return
        secure.setKey(prefs.provider, k)
        keyInput.setText("")
        changed = true
        refreshProvider()
        showTest("Saved. Tap Test key to check it.", Ui.MUTED)
    }

    /**
     * One tiny real request, so a mistyped key is caught here rather than
     * mid-conversation. On failure it shows the provider's exact error text.
     */
    private fun testKey() {
        val typed = keyInput.text.toString().trim()
        if (typed.isEmpty() && !secure.hasKey(prefs.provider)) { showTest("Enter or save a key first.", Ui.WARN); return }
        prefs.homeCity = homeCityInput.text.toString()
        val name = Assistants.providerName(prefs)
        showTest("Testing $name…", Ui.MUTED)
        thread(name = "key-test") {
            val result = Assistants.create(this, prefs, keyOverride = typed)!!
                .use { it.ask("Reply with just the word OK.", object : AnswerEvents {}) }
            main.post {
                if (result.ok) showTest("Key works. $name replied \u201C${result.text.take(40)}\u201D in ${result.totalMs} ms" +
                    (if (result.detail.isNotEmpty()) " (${result.detail})." else "."), Ui.GOOD)
                else showTest(result.text + if (result.detail.isNotEmpty()) "\n\nDetails: ${result.detail}" else "", Ui.BAD)
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
