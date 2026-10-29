package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanPhoneKeySiriGateTest {
    @Test fun suppressesDuplicatePressesButAllowsLaterPressAndFailedSubmissionRetry() {
        val gate = NissanPhoneKeySiriGate()
        val session = Any()
        assertTrue(gate.claim(session, 10_000))
        assertFalse(gate.claim(session, 10_500))
        assertTrue(gate.claim(session, 11_200))
        gate.release(session, 11_200)
        assertTrue(gate.claim(session, 11_300))
        assertTrue(gate.claim(Any(), 11_400))
    }
}
