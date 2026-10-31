package com.shilapi.xcertplay

import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R

/** Connection setup cards; Bluetooth, USB, and hotspot actions stay with the activity. */
internal class DiPlayConnectionBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val sections: DiPlaySettingsSectionBuilder,
    private val actions: Actions,
) {
    data class Actions(
        val wirelessControls: (LinearLayout) -> Unit,
        val choosePhone: () -> Unit,
        val bluetoothSettings: () -> Unit,
        val reviewPermissions: () -> Unit,
        val connectWireless: () -> Unit,
        val connectUsb: () -> Unit,
    )

    fun build(content: LinearLayout) = with(ui) {
        content.addView(label(activity.getString(R.string.connection_setup), 34, DiPlayCardUi.TEXT, true))
        content.addView(label(activity.getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha),
            17, DiPlayCardUi.MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        sections.add(content, activity.getString(R.string.s_1_choose_your_connection), null, false,
            actions.wirelessControls)
        sections.add(content, activity.getString(R.string.s_2_pair_your_iphone), null, false) { card ->
            card.addView(label(activity.getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car),
                16, DiPlayCardUi.MUTED))
            card.addView(button("${activity.getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(activity)}",
                false, actions.choosePhone), matchButton(12, 60))
            card.addView(button(activity.getString(R.string.bluetooth_settings), false,
                actions.bluetoothSettings), matchButton(12, 60))
            card.addView(button(activity.getString(R.string.review_app_permissions), false,
                actions.reviewPermissions), matchButton(12, 60))
        }
        sections.add(content, activity.getString(R.string.s_3_connect), null, false) { card ->
            card.addView(label(activity.getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the),
                16, DiPlayCardUi.MUTED))
            card.addView(button(activity.getString(R.string.connect_phone), true,
                actions.connectWireless), matchButton(12, 60))
        }
        sections.add(content, activity.getString(R.string.prefer_a_cable), null, false) { card ->
            card.addView(label(activity.getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo),
                16, DiPlayCardUi.MUTED))
            card.addView(button(activity.getString(R.string.connect_with_usb), false,
                actions.connectUsb), matchButton(12, 60))
        }
    }
}
