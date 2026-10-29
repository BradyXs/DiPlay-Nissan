package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.shouldResetLinkOnPeerSilence
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayMediaActivityTest {
    @After fun restoreClock() {
        AirPlayMediaActivity.clock = System::nanoTime
        AirPlayMediaActivity.resetForTest()
    }

    @Test fun recentMediaPreventsControlSilenceResetUntilMediaStops() {
        var now = 1_000_000_000L
        AirPlayMediaActivity.clock = { now }
        AirPlayMediaActivity.resetForTest()
        assertTrue(shouldResetLinkOnPeerSilence(true, AirPlayMediaActivity.recent()))
        AirPlayMediaActivity.onPayload()
        now += 14_000_000_000L
        assertFalse(shouldResetLinkOnPeerSilence(true, AirPlayMediaActivity.recent()))
        now += 1_000_000_000L
        assertTrue(shouldResetLinkOnPeerSilence(true, AirPlayMediaActivity.recent()))
        assertFalse(shouldResetLinkOnPeerSilence(false, null))
    }
}
