package com.shilapi.xcertplay

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat

/** One settings section: heading, card body, spacing, and search title. */
internal class DiPlaySettingsSectionBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
) {
    fun add(parent: LinearLayout, title: String, icon: Int?, normalize: Boolean,
        build: (LinearLayout) -> Unit) = with(ui) {
        index(title)
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, icon)?.let {
                DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, DiPlayCardUi.ACCENT) }
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, DiPlayCardUi.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        if (normalize) normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
}
