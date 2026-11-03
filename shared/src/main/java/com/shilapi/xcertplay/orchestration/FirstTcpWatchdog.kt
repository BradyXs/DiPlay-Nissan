package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class FirstTcpWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
    private val sessionTimeoutMillis: Long = WirelessStartupPolicy.SESSION_AFTER_TCP_MILLIS,
    private val onSessionTimeout: () -> Unit = onTimeout,
) {
    @Volatile var terminated = false
        private set
    private var firstStartNanos: Long? = null
    private var firstAcceptNanos: Long? = null
    private var success = false
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent(sentAtNanos: Long) {
        if (terminated || firstStartNanos != null) return
        firstStartNanos = sentAtNanos
        log("StartSession atNs=$sentAtNanos")
        if (success) return
        if (firstAcceptNanos != null) {
            armSessionTimeout()
            return
        }
        val remaining = (timeoutMillis - (nowNanos() - sentAtNanos) / 1_000_000).coerceAtLeast(0)
        cancelTimer = schedule(remaining, ::expire)
    }

    @Synchronized fun accepted(event: AirPlayTcpAccepted): Boolean {
        if (terminated || event.listener !== listener || event.internal) return false
        if (firstAcceptNanos == null) {
            firstAcceptNanos = event.acceptedAtNanos
            log("first TCP atNs=${event.acceptedAtNanos}")
            if (firstStartNanos != null && !success) {
                cancel()
                armSessionTimeout()
            }
        }
        return true
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (terminated) return false
        if (!success) log("session established atNs=${nowNanos()}")
        success = true
        cancel()
        return true
    }

    @Synchronized fun terminate(): Boolean {
        if (terminated) return false
        terminated = true
        cancel()
        return true
    }

    private fun expire() {
        synchronized(this) {
            if (terminated || success || firstAcceptNanos != null || firstStartNanos == null) return
            terminated = true
            cancelTimer = null
            log("first TCP timeout atNs=${nowNanos()} startNs=$firstStartNanos")
        }
        onTimeout()
    }

    private fun armSessionTimeout() {
        if (cancelTimer != null || terminated || success) return
        log("TCP accepted; waiting for AirPlay session timeoutMs=$sessionTimeoutMillis")
        cancelTimer = schedule(sessionTimeoutMillis, ::expireSession)
    }

    private fun expireSession() {
        synchronized(this) {
            if (terminated || success || firstAcceptNanos == null || firstStartNanos == null) return
            terminated = true
            cancelTimer = null
            log("AirPlay session timeout after TCP atNs=${nowNanos()} acceptedNs=$firstAcceptNanos")
        }
        onSessionTimeout()
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }
}
