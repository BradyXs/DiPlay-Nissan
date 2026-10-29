package com.shilapi.xcertplay

import com.shilapi.xcertplay.NissanSeekAudioRouting.Target
import com.shilapi.xcertplay.orchestration.CarPlayCallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanSeekAudioRoutingTest {
    @Test fun assistantDoesNotAdjustNavigationVolume() {
        assertEquals(Target.BLOCKED, NissanSeekAudioRouting.target(null, "assistant"))
    }
    @Test fun activeCallTakesPriorityOverGuidanceAndRingingBlocksMusicKeys() {
        assertEquals(Target.CALL, NissanSeekAudioRouting.target(CarPlayCallPhase.ACTIVE, "navigation"))
        assertEquals(Target.CALL, NissanSeekAudioRouting.target(CarPlayCallPhase.DIALING, "media"))
        assertEquals(Target.BLOCKED, NissanSeekAudioRouting.target(CarPlayCallPhase.RINGING, "navigation"))
    }

    @Test fun guidanceGetsVolumeOnlyWhileItsAudioStreamIsActive() {
        assertEquals(Target.NAVIGATION, NissanSeekAudioRouting.target(null, "navigation"))
        assertEquals(Target.CALL, NissanSeekAudioRouting.target(null, "call"))
        assertEquals(Target.MEDIA, NissanSeekAudioRouting.target(null, "media"))
        assertEquals(Target.MEDIA, NissanSeekAudioRouting.target(null, "none"))
    }

    @Test fun previousDecreasesNextIncreasesAndBothClampToSliderRange() {
        assertEquals(45, NissanSeekAudioRouting.adjustedPercent(50, seekUp = true))
        assertEquals(55, NissanSeekAudioRouting.adjustedPercent(50, seekUp = false))
        assertEquals(0, NissanSeekAudioRouting.adjustedPercent(0, seekUp = true))
        assertEquals(100, NissanSeekAudioRouting.adjustedPercent(100, seekUp = false))
    }

    @Test fun rapidDuplicateReportsCannotSweepVolumeButOppositeKeyCanCorrectIt() {
        val gate = NissanSeekVolumeGate()
        assertTrue(gate.claim(32, Target.NAVIGATION, 1_000))
        assertFalse(gate.claim(32, Target.NAVIGATION, 1_100))
        assertTrue(gate.claim(31, Target.NAVIGATION, 1_150))
        assertTrue(gate.claim(32, Target.CALL, 1_200))
        assertTrue(gate.claim(32, Target.CALL, 1_450))
    }
}
