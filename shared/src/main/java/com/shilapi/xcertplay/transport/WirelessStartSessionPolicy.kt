package com.shilapi.xcertplay.transport

/** Chooses when to send 0x4301 while retaining the explicit 0x4300 request as a fallback. */
internal class WirelessStartSessionPolicy(private val allowEarlyStart: Boolean) {
    enum class Trigger { WIRELESS_AVAILABLE, CARPLAY_REQUEST }

    private var startWasSent = false

    @Synchronized
    fun onWirelessAvailability(available: Boolean): Trigger? {
        if (!allowEarlyStart || !available || startWasSent) return null
        startWasSent = true
        return Trigger.WIRELESS_AVAILABLE
    }

    @Synchronized
    fun onCarPlayAvailability(connectionPending: Boolean): Trigger? {
        if (startWasSent && connectionPending) return null
        startWasSent = true
        return Trigger.CARPLAY_REQUEST
    }
}
