package com.shilapi.xcertplay

import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Builds the home page and its connection card without owning connection state. */
internal class DiPlayHomeBuilder(
    private val activity: ComponentActivity,
    private val ui: DiPlayCardUi,
    private val actions: Actions,
) {
    data class Actions(
        val wireless: () -> Unit,
        val usb: () -> Unit,
        val settings: () -> Unit,
        val disconnect: () -> Unit,
        val choosePhone: () -> Unit,
        val hotspotSettings: () -> Unit,
    )

    data class Widgets(val status: TextView, val connect: Button, val disconnect: Button)

    fun build(content: LinearLayout, compact: Boolean, setupError: String?,
        hotspotOff: Boolean, version: String): Widgets = with(ui) {
        if (compact) return buildCompact(content, setupError)

        val wide = activity.resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(activity.getString(R.string.your_phone_your_drive), 12, DiPlayCardUi.ACCENT, true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .16f
        })
        left.addView(label(activity.getString(R.string.a_familiar_drive), if (wide) 42 else 36,
            DiPlayCardUi.TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(activity.getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo),
            19, DiPlayCardUi.MUTED))

        val card = card()
        card.addView(label(activity.getString(R.string.wireless_carplay), 12, DiPlayCardUi.ACCENT, true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .12f
        })
        val status = label(activity.getString(R.string.ready_when_you_are), 24, DiPlayCardUi.TEXT, true).apply {
            setPadding(0, dp(10), 0, dp(16))
        }
        card.addView(status)
        val connect = button(activity.getString(R.string.connect_phone), true, actions.wireless)
        card.addView(connect, matchButton())
        val hint = when (AirPlayPersistence.loadWirelessHotspotMode(activity)) {
            WirelessHotspotMode.EXISTING_WIFI -> activity.getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> activity.getString(R.string.hotspot_hint_manual)
            else -> activity.getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(hint, 15, DiPlayCardUi.MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (hotspotOff) {
            card.addView(label(activity.getString(R.string.msg_car_hotspot_off,
                AirPlayPersistence.loadManualHotspotSsid(activity)), 15, DiPlayCardUi.WARNING).apply {
                setPadding(0, dp(14), 0, 0)
            })
            card.addView(button(activity.getString(R.string.open_car_hotspot_settings), false,
                actions.hotspotSettings), matchButton(10, 56))
        }
        card.addView(button(activity.getString(R.string.choose_iphone), false, actions.choosePhone), matchButton(16, 56))
        val disconnect = button(activity.getString(R.string.disconnect), false, actions.disconnect).apply {
            visibility = View.GONE
        }
        card.addView(disconnect, matchButton(10, 56))

        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = activity.getString(R.string.carplay_icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(activity.getString(R.string.connect_with_usb), false, actions.usb), matchButton())
        right.addView(label(activity.getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y),
            14, DiPlayCardUi.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24))
        })
        right.addView(button(activity.getString(R.string.settings), false, actions.settings), matchButton())
        right.addView(label(activity.getString(R.string.make_diplay_feel_right_for_your_car),
            14, DiPlayCardUi.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${activity.getString(R.string.home_public_preview)}$version", 12,
            DiPlayCardUi.MUTED).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .08f
        })
        if (wide) {
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, DiPlayCardUi.WARNING).apply {
            setPadding(0, dp(16), 0, 0)
        }) }
        content.addView(body)
        Widgets(status, connect, disconnect)
    }

    private fun buildCompact(content: LinearLayout, setupError: String?): Widgets = with(ui) {
        val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val status = label(activity.getString(R.string.ready_when_you_are), 16, DiPlayCardUi.TEXT, true).apply {
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(status)
        val connect = button(activity.getString(R.string.connect_phone), true, actions.wireless)
        card.addView(connect, matchButton(0, 44))
        val buttonRow = row().apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER_VERTICAL }
        buttonRow.addView(button(activity.getString(R.string.connect_with_usb), false, actions.usb),
            LinearLayout.LayoutParams(0, dp(38), 1f))
        buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        buttonRow.addView(button(activity.getString(R.string.settings), false, actions.settings),
            LinearLayout.LayoutParams(0, dp(38), 1f))
        card.addView(buttonRow)
        val disconnect = button(activity.getString(R.string.disconnect), false, actions.disconnect).apply {
            visibility = View.GONE
        }
        card.addView(disconnect, matchButton(8, 38))
        content.addView(card)
        setupError?.let { content.addView(label(it, 13, DiPlayCardUi.WARNING).apply {
            setPadding(0, dp(6), 0, 0)
        }) }
        Widgets(status, connect, disconnect)
    }
}
