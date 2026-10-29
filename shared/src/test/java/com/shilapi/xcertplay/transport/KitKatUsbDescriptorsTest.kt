package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class KitKatUsbDescriptorsTest {
    private val config = intArrayOf(9,2,32,0,1,6,0,128,50, 9,4,3,1,2,10,0,0,0,
        7,5,0x81,2,0,2,0, 7,5,2,2,0,2,0).map { it.toByte() }.toByteArray()

    @Test fun readsConfigurationValueInsteadOfArrayIndex() {
        val result = KitKatUsbDescriptors.parse(config).single()
        assertEquals(6, result.id)
        val iface = result.interfaces.single()
        assertEquals(3, iface.id)
        assertEquals(1, iface.alternate)
        assertEquals(listOf(0x81,2), iface.endpoints.map { it.address })
        assertEquals(512, iface.endpoints.first().packetSize)
    }

    @Test fun rejectsEveryTruncationAndZeroLengthDescriptor() {
        for (length in 1 until config.size) assertTrue("length=$length", KitKatUsbDescriptors.parse(config.copyOf(length)).isEmpty())
        assertTrue(KitKatUsbDescriptors.parse(config.copyOf().apply { this[9] = 0 }).isEmpty())
        assertTrue(KitKatUsbDescriptors.parse(config.copyOf().apply { this[2] = 31 }).isEmpty())
    }

    @Test fun separatesMultipleConfigurationsAndAlternateSettings() {
        val first = byteArrayOf(9,2,9,0,0,1,0,0,0)
        val parsed = KitKatUsbDescriptors.parse(first + config)
        assertEquals(listOf(1,6), parsed.map { it.id })
        assertTrue(parsed.first().interfaces.isEmpty())
        assertEquals(1, parsed.last().interfaces.single().alternate)
    }
}
