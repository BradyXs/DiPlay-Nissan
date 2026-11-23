package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NissanVideoFrameRateTest {
    private val wireless = NissanVideoFrameRate.Profile(true, 800, 480, false, false)
    private val healthy = NissanVideoFrameRate.PlaybackWindow(29.9, 0.98, 0.97, 4, 50, 0, true)
    private val overloaded = healthy.copy(queueP90Millis = 80, decodedFraction = 0.7)

    @Test fun raisesAndLowersTheNextSessionWithHysteresis() {
        val policy = NissanVideoFrameRate()
        val low = policy.begin(wireless)
        assertEquals(15, low.fps)
        repeat(2) { assertNull(low.observe(healthy)) }
        assertEquals(30, low.observe(healthy))
        assertEquals(15, low.fps)
        assertEquals(15, policy.begin(wireless.copy(wireless = false)).fps)

        val high = policy.begin(wireless)
        assertEquals(30, high.fps)
        assertNull(high.observe(overloaded))
        assertEquals(15, high.observe(overloaded))
        val lowAgain = policy.begin(wireless)
        assertEquals(15, lowAgain.fps)
        repeat(5) { assertNull(lowAgain.observe(healthy)) }
        assertEquals(30, lowAgain.observe(healthy))
    }

    @Test fun ignoresIdleInputGapsAndAnOldDecoder() {
        val policy = NissanVideoFrameRate()
        val old = policy.begin(wireless)
        val current = policy.begin(wireless)
        repeat(4) { assertNull(old.observe(healthy)) }
        repeat(2) { assertNull(current.observe(healthy)) }
        assertNull(current.observe(healthy.copy(visibleThroughout = false)))
        repeat(2) { assertNull(current.observe(healthy)) }
        assertNull(current.observe(healthy.copy(inputFps = 1.0)))
        repeat(2) { assertNull(current.observe(healthy)) }
        assertNull(current.observe(healthy.copy(maxArrivalGapMillis = 2_000)))
        assertEquals(15, policy.begin(wireless).fps)
    }
}
