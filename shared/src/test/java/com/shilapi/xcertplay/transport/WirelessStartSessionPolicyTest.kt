package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessStartSessionPolicyTest {
    @Test fun earlyNissanStartUsesPositiveWirelessUpdateAndDoesNotDuplicateAfterTcp() {
        val policy = WirelessStartSessionPolicy(allowEarlyStart = true)
        assertNull(policy.onWirelessAvailability(false))
        assertEquals(WirelessStartSessionPolicy.Trigger.WIRELESS_AVAILABLE,
            policy.onWirelessAvailability(true))
        assertNull(policy.onWirelessAvailability(true))
        assertNull(policy.onCarPlayAvailability(connectionPending = true))
    }

    @Test fun explicitCarPlayRequestStartsWhenEarlySignalIsAbsentOrTcpHasNotArrived() {
        val standard = WirelessStartSessionPolicy(allowEarlyStart = false)
        assertNull(standard.onWirelessAvailability(true))
        assertEquals(WirelessStartSessionPolicy.Trigger.CARPLAY_REQUEST,
            standard.onCarPlayAvailability(connectionPending = false))

        val early = WirelessStartSessionPolicy(allowEarlyStart = true)
        early.onWirelessAvailability(true)
        assertEquals(WirelessStartSessionPolicy.Trigger.CARPLAY_REQUEST,
            early.onCarPlayAvailability(connectionPending = false))
    }
}
