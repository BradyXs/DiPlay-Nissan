package com.shilapi.xcertplay

import android.graphics.Color

/** Keep the connecting screen dark in daylight and dimmer at night. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = if (night) {
            WaitingScreenColors(Color.rgb(2, 9, 19), Color.WHITE, Color.rgb(140, 158, 175))
        } else {
            WaitingScreenColors(Color.rgb(5, 21, 37), Color.WHITE, Color.rgb(155, 175, 190))
        }
    }
}
