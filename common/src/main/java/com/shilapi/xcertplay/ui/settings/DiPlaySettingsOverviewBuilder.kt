package com.shilapi.xcertplay.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.shilapi.xcertplay.compatibleRipple
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsCategory
import com.shilapi.xcertplay.ui.components.DiPlayCardUi

/** Presentation-only pieces shared by the overview and category pages in settings. */
internal class DiPlaySettingsOverviewBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val navigation: DiPlaySettingsNavigationBuilder,
    private val onSelectCategory: (SettingsCategory) -> Unit,
) {
    fun pageTitle(content: LinearLayout, title: String, subtitle: String, expanded: Boolean) {
        // The selected destination is already visible in the persistent left rail.
        if (expanded) return
        content.addView(ui.label(title, 26, DiPlayCardUi.TEXT, true))
        content.addView(ui.label(subtitle, 16, DiPlayCardUi.MUTED).apply {
            setPadding(0, ui.dp(6), 0, ui.dp(20))
        })
    }

    fun sectionHeading(title: Int, topPaddingDp: Int = 4, bottomPaddingDp: Int = 10) =
        ui.label(activity.getString(title), 18, DiPlayCardUi.TEXT, true).apply {
            setPadding(0, ui.dp(topPaddingDp), 0, ui.dp(bottomPaddingDp))
        }

    fun summaryGroup(destinations: List<Pair<SettingsCategory, Int>>): LinearLayout = ui.card().apply {
        setPadding(0, 0, 0, 0)
        destinations.forEachIndexed { index, (category, summary) ->
            if (index > 0) addView(View(activity).apply {
                setBackgroundColor(BORDER)
            }, LinearLayout.LayoutParams(-1, ui.dp(1)).apply {
                marginStart = ui.dp(16)
                marginEnd = ui.dp(16)
            })
            addView(summaryRow(category, activity.getString(summary)),
                LinearLayout.LayoutParams(-1, ui.dp(58)))
        }
    }

    fun utilitiesCard(): LinearLayout = ui.card().apply {
        setPadding(0, 0, 0, 0)
        addView(utilityRow(
            SettingsCategory.DIAGNOSTICS,
            R.drawable.ic_dp_diagnostics,
            activity.getString(R.string.settings_diagnostics_summary),
        ), LinearLayout.LayoutParams(-1, ui.dp(72)))
        addView(View(activity).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, ui.dp(1)).apply {
                marginStart = ui.dp(16)
                marginEnd = ui.dp(16)
            })
        addView(utilityRow(
            SettingsCategory.ADVANCED,
            R.drawable.ic_dp_advanced,
            activity.getString(R.string.settings_advanced_subtitle),
            warning = true,
        ), LinearLayout.LayoutParams(-1, ui.dp(72)))
    }

    private fun utilityRow(
        category: SettingsCategory,
        icon: Int,
        description: String,
        warning: Boolean = false,
    ): LinearLayout = ui.row().apply {
        val tint = if (warning) DiPlayCardUi.WARNING else DiPlayCardUi.ACCENT
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = activity.getString(R.string.settings_open_category,
            navigation.categoryTitle(category))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = LayerDrawable(arrayOf<Drawable>(
                RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), ui.focusRing(12)))
        }
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, icon))
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(14) })
        addView(ui.column().apply {
            addView(ui.label(navigation.categoryTitle(category), 17,
                if (warning) DiPlayCardUi.WARNING else DiPlayCardUi.TEXT, true))
            addView(ui.label(description, 13, DiPlayCardUi.MUTED).apply {
                setPadding(0, ui.dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_dp_chevron))
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.ACCENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginStart = ui.dp(12) })
        setOnClickListener { onSelectCategory(category) }
    }

    private fun summaryRow(category: SettingsCategory, summary: String): LinearLayout = ui.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(16), 0, ui.dp(14), 0)
        isClickable = true
        isFocusable = true
        background = compatibleRipple(ColorStateList.valueOf(RIPPLE), ColorDrawable(Color.TRANSPARENT), null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = LayerDrawable(arrayOf<Drawable>(
                RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), ui.focusRing(12)))
        }
        contentDescription = "${activity.getString(R.string.settings_open_category,
            navigation.categoryTitle(category))}. $summary"
        addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, navigation.categoryIcon(category)))
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.TEXT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
        addView(ui.label(navigation.categoryTitle(category), 17, DiPlayCardUi.TEXT, true),
            LinearLayout.LayoutParams(0, -1, 1f))
        addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_dp_chevron))
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.MUTED))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        setOnClickListener { onSelectCategory(category) }
    }

    companion object {
        private val BORDER = DiPlayCardUi.BORDER
        private const val RIPPLE = DiPlayCardUi.RIPPLE
    }
}
