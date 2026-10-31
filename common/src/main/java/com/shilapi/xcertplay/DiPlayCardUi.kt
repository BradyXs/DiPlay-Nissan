package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import kotlin.math.roundToInt

/** View and card primitives shared by the home, connection, and settings builders. */
internal class DiPlayCardUi(
    private val context: Context,
    private val onSearchTitle: (String) -> Unit = {},
) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    fun index(title: String) = onSearchTitle(title)
    fun card() = column().apply {
        background = rounded(SURFACE, BORDER)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(context).apply {
        isAllCaps = false
        textSize = 18f
        setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = compatibleRipple(ColorStateList.valueOf(RIPPLE),
            rounded(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0)
        minHeight = dp(56)
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        compoundDrawablePadding = dp(12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = focusRing()
        text = title
        onSearchTitle(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
    }
    fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /** A "Title · Value" button reads as a setting row; action buttons remain centred. */
    internal class SettingButton(context: Context) : Button(context) {
        var action = false

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(MUTED), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            super.setText(styled, BufferType.SPANNABLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val chevron = AppCompatResources.getDrawable(context, R.drawable.ic_dp_chevron)?.mutate()?.apply {
                DrawableCompat.setTint(this, ACCENT)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
        }
    }

    fun focusRing(radiusDp: Int = 20) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(3), ACCENT)
        })
    }
    fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), stroke)
    }
    fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply {
        topMargin = dp(top)
    }
    fun space(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
        tag = SPACER
    }

    /** Apply one vertical rhythm to controls, notes, and nested groups in a card. */
    fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                isToggleRow(child) -> {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }

    companion object {
        val BG = Color.rgb(12, 17, 27)
        val SURFACE = Color.rgb(21, 30, 44)
        val BUTTON = Color.rgb(31, 43, 61)
        val BORDER = Color.rgb(42, 56, 75)
        val ACCENT = SettingsTheme.CARD.accent
        val TEXT = SettingsTheme.CARD.textPrimary
        val MUTED = SettingsTheme.CARD.textSecondary
        val WARNING = Color.rgb(255, 196, 128)
        const val RIPPLE = 0x336F9FD9
        const val VALUE_SEPARATOR = " · "
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
    }
}
