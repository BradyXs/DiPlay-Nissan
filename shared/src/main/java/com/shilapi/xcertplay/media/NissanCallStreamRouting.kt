package com.shilapi.xcertplay.media

import android.media.AudioManager

/** Android stream choice for the Nissan call downlink; OEM phone volume is separate. */
enum class NissanCallStreamMode { AUTO, MEDIA, VOICE_CALL }

internal object NissanCallStreamRouting {
    fun streamType(mode: NissanCallStreamMode, androidVoiceLevel: Int?): Int = when (mode) {
        NissanCallStreamMode.MEDIA -> AudioManager.STREAM_MUSIC
        NissanCallStreamMode.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        NissanCallStreamMode.AUTO -> if (androidVoiceLevel != null && androidVoiceLevel > 0)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }
}
