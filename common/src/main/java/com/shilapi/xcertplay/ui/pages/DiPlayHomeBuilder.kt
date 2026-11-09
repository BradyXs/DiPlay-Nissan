package com.shilapi.xcertplay.ui.pages

import android.os.Build
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.NissanHomeArtworkView
import com.shilapi.xcertplay.connection.DiPlayPreferences
import com.shilapi.xcertplay.ui.components.DiPlayCardUi

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
        if (com.shilapi.xcertplay.host.BuildConfig.NISSAN_ANDROID_44_PROFILE) {
            return buildNissan(content, compact, setupError, hotspotOff, version)
        }
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

    private fun buildNissan(content: LinearLayout, compact: Boolean, setupError: String?,
        hotspotOff: Boolean, version: String): Widgets = with(ui) {
        val paired = DiPlayPreferences.phoneAddress(activity) != null
        val phoneName = if (paired) DiPlayPreferences.phoneName(activity)
            else activity.getString(R.string.choose_iphone)
        val body = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(if (compact) 4 else 14), dp(8), dp(10))
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(if (compact) 4 else 20)
        })
        val heroSize = dp(if (compact) 92 else 128)
        body.addView(NissanHomeArtworkView(activity, NissanHomeArtworkView.Kind.CAR).apply {
            contentDescription = activity.getString(R.string.carplay_icon)
        }, LinearLayout.LayoutParams(heroSize, heroSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val heading = row().apply { gravity = Gravity.CENTER }
        heading.addView(label("Apple CarPlay", if (compact) 24 else 30, DiPlayCardUi.TEXT, true))
        if (paired && setupError == null) {
            heading.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(52, 199, 89))
                }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { leftMargin = dp(9) })
        }
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val status = label(activity.getString(R.string.ready_when_you_are), if (compact) 14 else 16,
            DiPlayCardUi.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        fun connectionCard(wireless: Boolean): Pair<LinearLayout, Button> {
            val action = if (wireless) actions.wireless else actions.usb
            val card = column().apply {
                setPadding(dp(if (compact) 13 else 18), dp(if (compact) 11 else 16),
                    dp(if (compact) 13 else 18), dp(if (compact) 11 else 16))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.argb(25, 255, 255, 255), Color.argb(13, 255, 255, 255))).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.argb(38, 255, 255, 255))
                }
                isClickable = true
                isFocusable = true
                isEnabled = !wireless || setupError == null
                if (isEnabled) setOnClickListener { action() }
            }
            val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val symbol = NissanHomeArtworkView(activity, if (wireless)
                NissanHomeArtworkView.Kind.WIRELESS else NissanHomeArtworkView.Kind.USB).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            top.addView(symbol, LinearLayout.LayoutParams(dp(if (compact) 36 else 44),
                dp(if (compact) 36 else 44)))
            top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(label(activity.getString(if (wireless) R.string.home_wireless_recommended
                else R.string.home_wired_ready), 11, if (wireless) Color.rgb(131, 188, 255)
                else DiPlayCardUi.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                background = GradientDrawable().apply {
                    setColor(if (wireless) Color.argb(45, 0, 122, 255)
                        else Color.argb(29, 255, 255, 255))
                    cornerRadius = dp(14).toFloat()
                }
            }, LinearLayout.LayoutParams(-2, dp(27)))
            card.addView(top)
            card.addView(label(activity.getString(if (wireless) R.string.home_wireless_connection
                else R.string.home_wired_connection), if (compact) 16 else 19,
                DiPlayCardUi.TEXT, true), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(if (compact) 7 else 11)
            })
            card.addView(label(if (wireless) phoneName else activity.getString(R.string.home_usb_hint),
                if (compact) 12 else 13, DiPlayCardUi.MUTED).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            val button = button(activity.getString(if (wireless) R.string.home_connect
                else R.string.home_usb_connect), wireless, action).apply {
                textSize = if (compact) 15f else 16f
                isEnabled = !wireless || setupError == null
                if (wireless) setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (wireless) Color.rgb(0, 122, 255) else Color.argb(35, 255, 255, 255))
                    cornerRadius = dp(13).toFloat()
                    if (!wireless) setStroke(dp(1), Color.argb(35, 255, 255, 255))
                }
            }
            card.addView(button, matchButton(if (compact) 8 else 13, if (compact) 44 else 48))
            return card to button
        }
        val (wireless, connect) = connectionCard(true)
        val (wired, _) = connectionCard(false)
        val sideBySide = activity.resources.configuration.screenWidthDp >= 600
        val availableDp = (activity.resources.configuration.screenWidthDp - if (compact) 56 else 96)
            .coerceAtLeast(280)
        if (sideBySide) {
            val options = row().apply { gravity = Gravity.CENTER }
            options.addView(wireless, LinearLayout.LayoutParams(0, -2, 1f))
            options.addView(View(activity), LinearLayout.LayoutParams(dp(14), 1))
            options.addView(wired, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(options, LinearLayout.LayoutParams(dp(minOf(720, availableDp)), -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(if (compact) 14 else 24)
            })
        } else {
            body.addView(wireless, LinearLayout.LayoutParams(dp(minOf(360, availableDp)), -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14)
            })
            body.addView(wired, LinearLayout.LayoutParams(dp(minOf(360, availableDp)), -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
            })
        }
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(label(activity.getString(R.string.home_switch_iphone), 14,
            DiPlayCardUi.MUTED).apply {
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { actions.choosePhone() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(label("DiPlay · v${version.substringBefore('-')}", 12, DiPlayCardUi.MUTED),
            LinearLayout.LayoutParams(-2, -2))
        body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (hotspotOff) {
            body.addView(label(activity.getString(R.string.msg_car_hotspot_off,
                AirPlayPersistence.loadManualHotspotSsid(activity)), 15, DiPlayCardUi.WARNING).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            body.addView(button(activity.getString(R.string.open_car_hotspot_settings), false,
                actions.hotspotSettings), LinearLayout.LayoutParams(dp(260), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
            })
        }
        val disconnect = button(activity.getString(R.string.disconnect), false, actions.disconnect).apply {
            visibility = View.GONE
        }
        body.addView(disconnect, LinearLayout.LayoutParams(dp(220), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
        })
        setupError?.let {
            body.addView(label(it, 15, DiPlayCardUi.WARNING).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        Widgets(status, connect, disconnect)
    }

    private fun buildCompact(content: LinearLayout, setupError: String?): Widgets = with(ui) {
        val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val status = label(activity.getString(R.string.ready_when_you_are), 16, DiPlayCardUi.TEXT, true).apply {
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(status)
        val connect = button(activity.getString(R.string.connect_phone), true, actions.wireless)
        card.addView(connect, matchButton(0, 48))
        val buttonRow = row().apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER_VERTICAL }
        buttonRow.addView(button(activity.getString(R.string.connect_with_usb), false, actions.usb),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        buttonRow.addView(button(activity.getString(R.string.settings), false, actions.settings),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(buttonRow)
        val disconnect = button(activity.getString(R.string.disconnect), false, actions.disconnect).apply {
            visibility = View.GONE
        }
        card.addView(disconnect, matchButton(8, 48))
        content.addView(card)
        setupError?.let { content.addView(label(it, 13, DiPlayCardUi.WARNING).apply {
            setPadding(0, dp(6), 0, 0)
        }) }
        Widgets(status, connect, disconnect)
    }
}
