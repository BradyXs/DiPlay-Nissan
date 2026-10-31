package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NissanCallStreamRoutingTest {
    @Test fun fixedNissanVoiceStreamDoesNotDependOnCurrentVolume() {
        assertEquals(AudioManager.STREAM_VOICE_CALL,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.VOICE_CALL))
        assertEquals(AudioManager.STREAM_MUSIC,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.MEDIA))
    }
}
