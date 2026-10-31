package com.shilapi.xcertplay.media

import android.media.AudioManager

/** Android stream choice for the Nissan call downlink; OEM phone volume is separate. */
enum class NissanCallStreamMode { MEDIA, VOICE_CALL }

internal object NissanCallStreamRouting {
    fun streamType(mode: NissanCallStreamMode): Int = when (mode) {
        NissanCallStreamMode.MEDIA -> AudioManager.STREAM_MUSIC
        NissanCallStreamMode.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
    }
}
