package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmStartupPacketGateTest {
    @Test fun nissanTunnelOutputWaitsForCarPlayStartAndOpensOnce() {
        val gate = NcmStartupPacketGate(enabled = true)
        assertFalse(gate.allowPacket())
        gate.noteDiscardedPacket()
        gate.noteDiscardedPacket()
        assertEquals(2, gate.onStartSessionSent())
        assertTrue(gate.allowPacket())
        assertEquals(0, gate.onStartSessionSent())
    }

    @Test fun ordinaryNcmOutputRemainsUngated() {
        val gate = NcmStartupPacketGate(enabled = false)
        assertTrue(gate.allowPacket())
        gate.noteDiscardedPacket()
        assertEquals(0, gate.onStartSessionSent())
        assertTrue(gate.allowPacket())
    }
}
