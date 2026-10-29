package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NissanCallStreamRoutingTest {
    @Test fun selectionAllowsAudibleVoiceStreamAndExplicitComparison() {
        assertEquals(AudioManager.STREAM_VOICE_CALL,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.AUTO, 4))
        assertEquals(AudioManager.STREAM_MUSIC,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.AUTO, 0))
        assertEquals(AudioManager.STREAM_MUSIC,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.AUTO, null))
        assertEquals(AudioManager.STREAM_VOICE_CALL,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.VOICE_CALL, 0))
        assertEquals(AudioManager.STREAM_MUSIC,
            NissanCallStreamRouting.streamType(NissanCallStreamMode.MEDIA, 4))
    }
}
