package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayConnectionProgressTest {
    @Test fun wirelessMilestonesOnlyAdvanceAfterReportedEvents() {
        val progress = CarPlayConnectionProgress(wireless = true)
        assertEquals(5, progress.totalSteps)
        progress.onStatus(CarPlayStatus.StartingHotspot)
        assertEquals(0, progress.completedSteps)
        progress.onStatus(CarPlayStatus.MfiReady)
        assertEquals(1, progress.completedSteps)
        progress.onStatus(CarPlayStatus.HotspotReady("car", "5 GHz", 36, "", "", "manual"))
        assertEquals(2, progress.completedSteps)
        progress.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(2, progress.completedSteps)
        progress.onStatus(CarPlayStatus.RunningWireless)
        assertEquals(3, progress.completedSteps)
        progress.onSessionActive()
        assertEquals(4, progress.completedSteps)
        progress.onFirstFrame()
        assertEquals(5, progress.completedSteps)
    }

    @Test fun wiredMilestonesResetWhenTransportChanges() {
        val progress = CarPlayConnectionProgress(wireless = true)
        progress.onSessionActive()
        progress.reset(wireless = false)
        assertEquals(0, progress.completedSteps)
        progress.onStatus(CarPlayStatus.MfiReady)
        assertEquals(1, progress.completedSteps)
        progress.onStatus(CarPlayStatus.OpeningDataPaths)
        assertEquals(1, progress.completedSteps)
        progress.onStatus(CarPlayStatus.Pairing)
        assertEquals(2, progress.completedSteps)
        progress.onStatus(CarPlayStatus.ConnectingControl)
        assertEquals(3, progress.completedSteps)
        progress.onStatus(CarPlayStatus.Failed("test"))
        assertEquals(3, progress.completedSteps)
        progress.onSessionActive()
        progress.onFirstFrame()
        assertEquals(5, progress.completedSteps)
    }
}
