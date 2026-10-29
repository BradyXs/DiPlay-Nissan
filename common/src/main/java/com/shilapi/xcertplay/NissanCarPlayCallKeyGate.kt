package com.shilapi.xcertplay

/** Send each physical phone-key action at most once per reported call and controller. */
internal class NissanCarPlayCallKeyGate {
    private var sentController: Any? = null
    private var sentEpoch = -1L

    @Synchronized fun claim(controller: Any, epoch: Long): Boolean {
        if (sentController === controller && sentEpoch == epoch) return false
        sentController = controller
        sentEpoch = epoch
        return true
    }

    @Synchronized fun release(controller: Any, epoch: Long) {
        if (sentController === controller && sentEpoch == epoch) {
            sentController = null
            sentEpoch = -1L
        }
    }
}
