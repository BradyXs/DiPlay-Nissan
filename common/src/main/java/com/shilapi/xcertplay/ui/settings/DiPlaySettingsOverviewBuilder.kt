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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.shilapi.xcertplay.compatibleRipple
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsCategory
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.ui.components.DiPlayCardUi

/** Presentation-only pieces shared by the overview and category pages in settings. */
internal class DiPlaySettingsOverviewBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val navigation: DiPlaySettingsNavigationBuilder,
    private val onSelectCategory: (SettingsCategory) -> Unit,
) {
    data class QuickSettingItem(
        val icon: Int,
        val title: String,
        val description: String,
        val checked: Boolean? = null,
        val onCheckedChange: ((Boolean) -> Unit)? = null,
        val onClick: ((TextView) -> Unit)? = null,
    )

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

    /** Independent tiles for the four settings drivers reach for most often. */
    fun quickSettingsGrid(items: List<QuickSettingItem>): LinearLayout = ui.column().apply {
        val rows = items.chunked(2)
        rows.forEachIndexed { rowIndex, rowItems ->
            val itemRow = ui.row().apply { gravity = Gravity.TOP }
            rowItems.forEachIndexed { columnIndex, item ->
                if (columnIndex > 0) itemRow.addView(View(activity),
                    LinearLayout.LayoutParams(ui.dp(10), 1))
                itemRow.addView(quickSettingTile(item),
                    LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(itemRow, LinearLayout.LayoutParams(-1, -2).apply {
                if (rowIndex < rows.lastIndex) bottomMargin = ui.dp(10)
            })
        }
    }

    /** Compact links that keep the two non-category overview destinations available. */
    fun overviewFooter(
        languageValue: String,
        version: String,
        onLanguage: () -> Unit,
        onAbout: () -> Unit,
    ): LinearLayout = ui.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(footerAction(R.drawable.ic_dp_language,
            activity.getString(R.string.language_app_language), languageValue, onLanguage),
            LinearLayout.LayoutParams(0, ui.dp(62), 1f))
        addView(View(activity), LinearLayout.LayoutParams(ui.dp(12), 1))
        addView(footerAction(R.drawable.ic_dp_about,
            activity.getString(R.string.about_diplay), "v$version", onAbout),
            LinearLayout.LayoutParams(0, ui.dp(62), 1f))
    }

    private fun quickSettingTile(item: QuickSettingItem): View {
        val checked = item.checked
        if (checked != null) {
            val result = SettingsWidgets.createSwitchRow(
                context = activity,
                label = item.title,
                description = item.description,
                checked = checked,
                theme = SettingsTheme.CARD,
                contentDescription = listOf(item.title, item.description)
                    .filter(String::isNotBlank).joinToString(". "),
                labelSizeSp = 16f,
                onChanged = { item.onCheckedChange?.invoke(it) },
            )
            val row = result.rowView as LinearLayout
            row.setPadding(0, 0, 0, 0)
            row.minimumHeight = ui.dp(62)
            row.addView(icon(item.icon), 0,
                LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { marginEnd = ui.dp(10) })
            return FrameLayout(activity).apply {
                background = ui.rounded(SURFACE, BORDER)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                minimumHeight = ui.dp(82)
                addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            }
        }

        val description = ui.label(item.description, 13, DiPlayCardUi.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "${item.title}. ${item.description}"
            background = compatibleRipple(ColorStateList.valueOf(RIPPLE),
                ui.rounded(SURFACE, BORDER), null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = LayerDrawable(arrayOf<Drawable>(
                    RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), ui.focusRing(14)))
            }
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            minimumHeight = ui.dp(82)
            addView(icon(item.icon), LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply {
                marginEnd = ui.dp(10)
            })
            addView(ui.column().apply {
                addView(ui.label(item.title, 16, DiPlayCardUi.TEXT, true))
                addView(description.apply { setPadding(0, ui.dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_dp_chevron))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.MUTED))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(6) })
            setOnClickListener { item.onClick?.invoke(description) }
        }
    }

    private fun footerAction(iconRes: Int, title: String, value: String, onClick: () -> Unit): View =
        ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $value"
            background = compatibleRipple(ColorStateList.valueOf(RIPPLE),
                ui.rounded(SURFACE, BORDER), null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = ui.focusRing(14)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            addView(icon(iconRes), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply {
                marginEnd = ui.dp(10)
            })
            addView(ui.label(title, 15, DiPlayCardUi.TEXT, true),
                LinearLayout.LayoutParams(0, -1, 1f))
            addView(ui.label(value, 13, DiPlayCardUi.MUTED).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_dp_chevron))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.MUTED))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(4) })
            setOnClickListener { onClick() }
        }

    private fun icon(resource: Int) = ImageView(activity).apply {
        setImageDrawable(AppCompatResources.getDrawable(activity, resource)?.mutate())
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.ACCENT))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        private val SURFACE = DiPlayCardUi.SURFACE
        private const val RIPPLE = DiPlayCardUi.RIPPLE
    }
}
