package com.shilapi.xcertplay.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Prevents stale system tunnel traffic from stalling NCM before CarPlayStartSession. */
internal class NcmStartupPacketGate(enabled: Boolean) {
    private val started = AtomicBoolean(!enabled)
    private val discarded = AtomicInteger()

    fun allowPacket(): Boolean = started.get()

    fun noteDiscardedPacket() {
        discarded.incrementAndGet()
    }

    /** Opens once, returning how many pre-session tunnel packets were discarded. */
    fun onStartSessionSent(): Int {
        if (!started.compareAndSet(false, true)) return 0
        return discarded.getAndSet(0)
    }
}
