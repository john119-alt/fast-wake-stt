package com.fastwake.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small design system shared by the screens (no XML layouts). */
object Ui {
    val BG = Color.parseColor("#0E1116")
    val SURFACE = Color.parseColor("#181C23")
    val SURFACE_2 = Color.parseColor("#222833")
    val TEXT = Color.parseColor("#ECEFF4")
    val MUTED = Color.parseColor("#8B95A5")
    val ACCENT = Color.parseColor("#7C9CFF")
    val ACCENT_DARK = Color.parseColor("#3D5AFE")
    val GOOD = Color.parseColor("#3DD68C")
    val WARN = Color.parseColor("#FFB547")
    val BAD = Color.parseColor("#FF6B6B")
    val PURPLE = Color.parseColor("#B07CFF")

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radiusPx
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    fun Context.text(s: CharSequence, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    /** Filled pill button. */
    fun Context.primaryButton(label: String, color: Int = ACCENT_DARK, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                rounded(color, dp(28).toFloat()), null)
            stateListAnimator = null
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { onClick() }
        }

    /** Text-only button for headers. */
    fun Context.textButton(label: String, color: Int = ACCENT, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f; setTextColor(color)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                null, rounded(Color.WHITE, dp(18).toFloat()))
            stateListAnimator = null; minWidth = 0; minimumWidth = 0
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    fun Context.card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(18).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun Context.sectionTitle(s: String) = text(s.uppercase(), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.08f; setPadding(dp(4), dp(18), 0, dp(8))
    }

    fun Context.screen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(16), dp(20), dp(16), dp(12))
    }

    fun Context.headerRow(title: String, vararg actions: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(title, 26f, TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { addView(it) }
    }

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top }
}
