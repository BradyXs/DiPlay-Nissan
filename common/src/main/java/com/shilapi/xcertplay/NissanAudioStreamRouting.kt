package com.shilapi.xcertplay

import android.media.AudioManager

/** AudioTrack stream types verified in the Nissan KitKat firmware. */
internal object NissanAudioStreamRouting {
    // The factory framework calls this STREAM_HSAE. Its navigation sound preview uses stream 10.
    const val NAVIGATION = 10

    fun navigationStream(saved: Int, nissanProfile: Boolean): Int = when {
        nissanProfile -> NAVIGATION
        // Channel 14 belonged to another head unit and is not a valid stream on this ROM.
        saved == 14 -> AudioManager.STREAM_MUSIC
        else -> saved
    }
}
