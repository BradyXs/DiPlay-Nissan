package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDiagnosticsTest {
    @Test fun recordsRepeatedRouteFieldsAndEveryReceivedUpdate() {
        val frame = Iap2Messages.buildRaw(0x5201) {
            u8(1, 1)
            string(3, "中山路")
            string(4, "目的地")
            string(3, "第二个路名")
            u64(6, 120)
            bytes(13, byteArrayOf(0, 1, 0, 2))
            u8(24, 15)
        }
        val lines = mutableListOf<String>()
        repeat(2) { NavigationDiagnostics.received(it.toLong(), frame) { line -> lines.add(line) } }
        assertEquals(2, lines.size)
        for ((index, line) in lines.withIndex()) {
            assertTrue(line.contains("seq=$index msg=0x5201 fields=7"))
            assertTrue(line.contains("road[0x3]=\"中山路\""))
            assertTrue(line.contains("road[0x3]=\"第二个路名\""))
            assertTrue(line.contains("destination[0x4]=\"目的地\""))
            assertTrue(line.contains("remainingSeconds[0x6]=120"))
            assertTrue(line.contains("maneuverCursor[0xd]=[1,2]"))
            assertTrue(line.contains("unknown[0x18]=1B/u8:15"))
        }
    }

    @Test fun leavesKnownNavigationTextAvailableThroughTheLogRedactor() {
        val quoted = NavigationDiagnostics.quote("Passage\n走左道 192.168.2.1")
        val line = DiagnosticRedactor.redact("NAV_CLUSTER title=$quoted")
        assertNotNull(line)
        assertTrue(line!!.contains("assage\\u000a走左道 [ip]"))
        assertEquals(line, DiagnosticRedactor.redact(line))
    }
}
