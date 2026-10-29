package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanCarPlayCallKeyGateTest {
    @Test fun sendsOnlyOnceForEachCallAndAllowsRetryAfterSubmissionFails() {
        val gate = NissanCarPlayCallKeyGate()
        val controller = Any()
        assertTrue(gate.claim(controller, 1))
        assertFalse(gate.claim(controller, 1))
        gate.release(controller, 1)
        assertTrue(gate.claim(controller, 1))
        assertTrue(gate.claim(controller, 2))
        assertTrue(gate.claim(Any(), 2))
    }
}
