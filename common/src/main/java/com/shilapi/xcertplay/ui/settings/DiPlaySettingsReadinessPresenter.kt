package com.shilapi.xcertplay.ui.settings

import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.SettingsReadiness
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.components.DiPlayCardUi

/** Renders connection readiness into the overview card; the Activity supplies state and actions. */
internal class DiPlaySettingsReadinessPresenter(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val onChoosePhone: () -> Unit,
    private val onOpenConnectionSetup: () -> Unit,
) {
    private var card: LinearLayout? = null
    private var renderedState: SettingsReadiness? = null

    fun create(expanded: Boolean): LinearLayout = ui.card().apply {
        setPadding(ui.dp(20), ui.dp(if (expanded) 16 else 12), ui.dp(20), ui.dp(if (expanded) 16 else 12))
    }.also {
        card = it
        renderedState = null
    }

    fun clear() {
        card = null
        renderedState = null
    }

    fun render(state: SettingsReadiness, setupError: String?, phoneName: String) {
        val target = card ?: return
        if (state == renderedState) return
        renderedState = state
        target.removeAllViews()

        val title = activity.getString(when (state) {
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> R.string.setup_needs_attention
            SettingsReadiness.CONNECTED -> R.string.carplay_connected
            SettingsReadiness.CONNECTING -> R.string.connecting_to_your_iphone
            SettingsReadiness.CHOOSE_IPHONE -> R.string.settings_choose_iphone_title
            SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
        })
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> setupError.orEmpty()
            SettingsReadiness.HOTSPOT_SETUP -> activity.getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            SettingsReadiness.CONNECTED -> activity.getString(R.string.settings_ready_connected)
            SettingsReadiness.CONNECTING -> activity.getString(R.string.settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> activity.getString(R.string.settings_choose_iphone_description)
            SettingsReadiness.READY_WIRELESS -> activity.getString(R.string.settings_ready_wireless, phoneName)
            SettingsReadiness.READY_USB -> activity.getString(R.string.settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> DiPlayCardUi.WARNING
            state == SettingsReadiness.CONNECTED -> READY
            else -> DiPlayCardUi.TEXT
        }
        target.addView(ui.label(title, 22, tint, true))
        target.addView(ui.label(detail, 15, DiPlayCardUi.MUTED).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> target.addView(ui.button(
                activity.getString(R.string.settings_choose_iphone_action), true, onChoosePhone,
            ), ui.matchButton(12, 56))
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> target.addView(ui.button(
                activity.getString(R.string.open_connection_setup), true, onOpenConnectionSetup,
            ), ui.matchButton(12, 56))
            else -> Unit
        }
    }

    private companion object {
        const val READY = 0xFF7FCD9A.toInt()
    }
}
