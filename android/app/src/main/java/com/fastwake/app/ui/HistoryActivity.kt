package com.fastwake.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fastwake.app.data.HistoryStore
import com.fastwake.app.ui.Ui.card
import com.fastwake.app.ui.Ui.dp
import com.fastwake.app.ui.Ui.headerRow
import com.fastwake.app.ui.Ui.lp
import com.fastwake.app.ui.Ui.screen
import com.fastwake.app.ui.Ui.text
import com.fastwake.app.ui.Ui.textButton
import java.text.DateFormat
import java.util.Date

/** Past conversations, stored only on this phone. Tap one to expand it. */
class HistoryActivity : Activity() {

    private lateinit var store: HistoryStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        store = HistoryStore(this)

        val root = screen()
        root.addView(headerRow("History",
            textButton("Clear", Ui.BAD) { confirmClear() },
            textButton("Done") { finish() },
        ))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        list.removeAllViews()
        val sessions = store.list().filter { s -> s.entries.any { it.role == "user" } }
        if (sessions.isEmpty()) {
            list.addView(text("No conversations yet.", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) })
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (s in sessions) {
            val c = card()
            c.addView(text(fmt.format(Date(s.id)), 12f, Ui.MUTED))
            c.addView(text(s.title, 16f, Ui.TEXT, bold = true).apply { maxLines = 2 })
            val asked = s.entries.count { it.role == "user" }
            c.addView(text(if (asked == 1) "1 question" else "$asked questions", 12f, Ui.MUTED))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            for (e in s.entries) {
                val who = if (e.role == "user") "You" else "FastWake"
                details.addView(text(who, 12f, if (e.role == "user") Ui.ACCENT else Ui.GOOD, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                details.addView(text(e.text, 14f, Ui.TEXT).apply { setTextIsSelectable(true) })
                if (e.meta.isNotEmpty() && e.meta != "error") details.addView(text(e.meta, 11f, Ui.MUTED))
            }
            c.addView(details)
            c.setOnClickListener { details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE }
            list.addView(c, lp(top = dp(10)))
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("This deletes all saved conversations from this phone.")
            .setPositiveButton("Clear") { _, _ -> store.clear(); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
