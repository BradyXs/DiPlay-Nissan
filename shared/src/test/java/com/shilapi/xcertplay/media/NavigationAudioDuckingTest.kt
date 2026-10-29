package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioDuckingTest {
    @Test fun guidanceDucksOnlyMediaAndRestoresTheLatestUserLevel() {
        val volumes = CarPlayRelativeVolumes(media = 80, navigation = 70, call = 60)
        assertEquals(0.8f, volumes.forChannel(AudioChannel.MEDIA), 0.0001f)
        assertTrue(volumes.setNavigationDucking(true))
        assertFalse(volumes.setNavigationDucking(true))
        assertEquals(0.32f, volumes.forChannel(AudioChannel.MEDIA), 0.0001f)
        assertEquals(0.7f, volumes.forChannel(AudioChannel.NAVIGATION), 0.0001f)
        assertEquals(0.6f, volumes.forChannel(AudioChannel.PHONE), 0.0001f)
        volumes.update(media = 50, navigation = 70, call = 60)
        assertEquals(0.2f, volumes.forChannel(AudioChannel.MEDIA), 0.0001f)
        assertTrue(volumes.setNavigationDucking(false))
        assertEquals(0.5f, volumes.forChannel(AudioChannel.MEDIA), 0.0001f)
    }
}
