package com.shilapi.xcertplay

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NissanAudioStreamRoutingTest {
    @Test fun nissanGuidanceUsesFactoryHsaeStreamRegardlessOfOldPreference() {
        for (saved in listOf(AudioManager.STREAM_MUSIC, 10, 14)) {
            assertEquals(10, NissanAudioStreamRouting.navigationStream(saved, nissanProfile = true))
        }
    }

    @Test fun otherBuildsRetainTheirSavedStreamBehavior() {
        assertEquals(3, NissanAudioStreamRouting.navigationStream(3, nissanProfile = false))
        assertEquals(7, NissanAudioStreamRouting.navigationStream(7, nissanProfile = false))
        assertEquals(3, NissanAudioStreamRouting.navigationStream(14, nissanProfile = false))
    }
}
