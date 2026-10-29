package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayCallStateTest {
    private fun update(status: Int, id: String? = null) = Iap2Messages.buildRaw(0x4155) {
        u8(2, status)
        if (id != null) string(4, id)
    }

    @Test fun reconnectAndRepeatedCallsCannotReuseTheHangupEpoch() {
        val state = CarPlayCallState()
        state.accept(update(2))
        assertEquals(CarPlayCallPhase.RINGING, state.current()?.phase)
        val ringingEpoch = state.current()!!.epoch
        state.accept(update(4))
        assertEquals(ringingEpoch, state.current()!!.epoch)
        assertEquals(CarPlayCallPhase.ACTIVE, state.current()?.phase)
        state.accept(update(0))
        assertNull(state.current())
        state.accept(update(4))
        assertNotEquals(ringingEpoch, state.current()!!.epoch)
        state.clear()
        assertNull(state.current())
    }

    @Test fun ringingCallTakesPriorityOverAnExistingActiveCall() {
        val state = CarPlayCallState()
        state.accept(update(4, "first"))
        val activeEpoch = state.current()!!.epoch
        state.accept(update(2, "second"))
        assertEquals(CarPlayCallPhase.RINGING, state.current()?.phase)
        state.accept(update(0, "second"))
        assertEquals(activeEpoch, state.current()?.epoch)
    }
}
