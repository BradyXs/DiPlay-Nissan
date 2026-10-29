package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanPhoneKeyRoutingTest {
    @Test fun factoryNavigationKeysStayWithTheFactory() {
        assertFalse(NissanPhoneKeyProbe.shouldDispatchToNiPlay(0)) // Back
        assertFalse(NissanPhoneKeyProbe.shouldDispatchToNiPlay(2)) // Home
    }

    @Test fun carPlayControlsKeepTheirOemRelay() {
        listOf(6, 7, 26, 27, 28, 29, 31, 32).forEach {
            assertTrue(NissanPhoneKeyProbe.shouldDispatchToNiPlay(it))
        }
    }
}
