package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayRouteGuidanceTest {
    @Test
    fun retainsIncrementalGuidanceAndClearsAtRouteEnd() {
        val guidance = CarPlayRouteGuidance()
        guidance.accept(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1)
            string(3, "Current road")
            u32(10, 300)
            u64(6, 600)
        })
        val maneuver = guidance.accept(Iap2Messages.buildRaw(0x5202) {
            u16(1, 0)
            string(2, "Turn right")
            string(4, "Next road")
        })!!
        assertTrue(maneuver.active)
        assertEquals("Current road", maneuver.currentRoad)
        assertEquals(300L, maneuver.distanceToNextManeuverMeters)
        assertEquals(600L, maneuver.remainingTimeSeconds)
        assertEquals("Turn right", maneuver.nextManeuver?.description)
        assertEquals("Next road", maneuver.nextManeuver?.afterRoadName)

        val ended = guidance.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 0) })!!
        assertFalse(ended.active)
        assertNull(ended.nextManeuver)
        assertNull(ended.currentRoad)
        assertNull(ended.distanceToNextManeuverMeters)
    }

    @Test
    fun malformedIncrementalUpdateDoesNotPartiallyChangeRetainedRoute() {
        val guidance = CarPlayRouteGuidance()
        guidance.accept(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1)
            string(3, "Original road")
            u32(10, 300)
        })
        val before = guidance.snapshot()
        assertNull(guidance.accept(Iap2Messages.buildRaw(0x5201) {
            string(3, "Unexpected road")
            bytes(10, byteArrayOf(1, 2)) // Distance requires four bytes.
        }))
        assertEquals(before, guidance.snapshot())
    }

    @Test
    fun laneTextRequiresTheCurrentlyVisibleLaneIndex() {
        val guidance = CarPlayRouteGuidance()
        guidance.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16(16, 2); u8(18, 1) })
        assertNull(guidance.accept(Iap2Messages.buildRaw(0x5204) { u16(1, 3); string(3, "other lane") }))
        val shown = guidance.accept(Iap2Messages.buildRaw(0x5204) { u16(1, 2); string(3, "直行车道") })!!
        assertEquals("直行车道", shown.laneDescription)
        val hidden = guidance.accept(Iap2Messages.buildRaw(0x5201) { u8(18, 0) })!!
        assertNull(hidden.laneDescription)
        guidance.clear()
        assertNull(guidance.snapshot().laneDescription)
    }
}
