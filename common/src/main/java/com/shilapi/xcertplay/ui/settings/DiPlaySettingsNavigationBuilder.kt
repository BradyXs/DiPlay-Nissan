package com.shilapi.xcertplay.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.shilapi.xcertplay.SettingsLayoutPolicy
import com.shilapi.xcertplay.compatibleRipple
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsCategory
import com.shilapi.xcertplay.ui.components.DiPlayCardUi

/** Builds settings navigation chrome; category state and navigation remain owned by the Activity. */
internal class DiPlaySettingsNavigationBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val selectedCategory: () -> SettingsCategory,
    private val onSelectCategory: (SettingsCategory) -> Unit,
) {
    data class ExpandedShell(
        val root: LinearLayout,
        val railScroll: ScrollView,
    )

    fun header(compact: Boolean, onBack: () -> Unit): LinearLayout = ui.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        val backTargetWidth = ui.dp(64)
        val backTargetHeight = ui.dp(56)
        val backButton = FrameLayout(activity).apply {
            contentDescription = activity.getString(R.string.back)
            isClickable = true
            isFocusable = true
            val normalFace = GradientDrawable().apply {
                setColor(Color.argb(24, 255, 255, 255))
                cornerRadius = ui.dp(18).toFloat()
                setStroke(ui.dp(1), Color.argb(54, 255, 255, 255))
            }
            val pressedFace = GradientDrawable().apply {
                setColor(Color.argb(66, 44, 159, 245))
                cornerRadius = ui.dp(18).toFloat()
                setStroke(ui.dp(1), Color.argb(190, 76, 181, 255))
            }
            val focusedFace = GradientDrawable().apply {
                setColor(Color.argb(44, 44, 159, 245))
                cornerRadius = ui.dp(18).toFloat()
                setStroke(ui.dp(2), DiPlayCardUi.ACCENT)
            }
            background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                RippleDrawable(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(Color.argb(94, 76, 181, 255), Color.argb(72, 76, 181, 255)),
                    ),
                    normalFace,
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = ui.dp(18).toFloat()
                    },
                )
            } else {
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pressedFace)
                    addState(intArrayOf(android.R.attr.state_focused), focusedFace)
                    addState(intArrayOf(), normalFace)
                }
            }
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_dp_back))
                setColorFilter(DiPlayCardUi.ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
            setOnClickListener { onBack() }
        }
        addView(backButton, LinearLayout.LayoutParams(backTargetWidth, backTargetHeight))
        addView(ui.label(activity.getString(R.string.settings), if (compact) 22 else 27,
            DiPlayCardUi.TEXT, true).apply {
            setPadding(ui.dp(10), 0, ui.dp(12), 0)
        }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
    }

    fun expandedShell(header: View, categoryScroll: ScrollView, reconnectBar: View,
        settingsBlockGapDp: Int): ExpandedShell {
        val railScroll = ScrollView(activity).apply {
            addView(createRail())
        }
        val root = ui.column().apply {
            setBackgroundColor(DiPlayCardUi.BG)
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0)
            addView(header)
            addView(ui.space(18))
            addView(reconnectBar, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = ui.dp(settingsBlockGapDp)
            })
            val split = ui.row().apply { gravity = Gravity.TOP }
            // The rail scrolls independently when the display is too short for every category.
            split.addView(railScroll,
                LinearLayout.LayoutParams(
                    ui.dp(SettingsLayoutPolicy.railWidthDp(activity.resources.configuration.fontScale)), -2,
                ))
            split.addView(ui.space(18), LinearLayout.LayoutParams(ui.dp(18), 1))
            split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
            addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return ExpandedShell(root, railScroll)
    }

    fun createRail(): LinearLayout = ui.column().apply {
        background = ui.rounded(RAIL_SURFACE, RAIL_SURFACE)
        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
        SettingsCategory.entries.forEach { category ->
            addView(createDestination(category), ui.matchButton(0, 52).apply {
                bottomMargin = ui.dp(2)
            })
        }
    }

    fun categoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_vehicle
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
    }

    fun categoryTitle(category: SettingsCategory): String = activity.getString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
    })

    private fun createDestination(category: SettingsCategory): View {
        val selected = selectedCategory() == category
        val title = categoryTitle(category)
        return ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = ui.focusRing(12)
            contentDescription = activity.getString(R.string.settings_open_category, title)
            background = compatibleRipple(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    setColor(if (selected) RAIL_SELECTED else Color.TRANSPARENT)
                    cornerRadius = ui.dp(12).toFloat()
                },
                null,
            )
            setPadding(ui.dp(14), 0, ui.dp(12), 0)
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, categoryIcon(category)))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(DiPlayCardUi.TEXT))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { marginEnd = ui.dp(12) })
            addView(ui.label(title, 17, DiPlayCardUi.TEXT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setOnClickListener { onSelectCategory(category) }
        }
    }

    companion object {
        private val RAIL_SURFACE = Color.rgb(36, 40, 48)
        private val RAIL_SELECTED = Color.rgb(27, 143, 235)
        private const val RIPPLE = DiPlayCardUi.RIPPLE
    }
}
