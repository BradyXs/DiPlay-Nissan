package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayCallPhase

/** Routes OEM seek keys by the CarPlay sound currently in use. */
internal object NissanSeekAudioRouting {
    enum class Target { MEDIA, NAVIGATION, CALL, BLOCKED }

    fun target(call: CarPlayCallPhase?, activeAudioChannel: String): Target = when {
        call == CarPlayCallPhase.ACTIVE || call == CarPlayCallPhase.DIALING -> Target.CALL
        call == CarPlayCallPhase.RINGING -> Target.BLOCKED
        activeAudioChannel == "call" -> Target.CALL
        activeAudioChannel == "assistant" -> Target.BLOCKED
        activeAudioChannel == "navigation" -> Target.NAVIGATION
        else -> Target.MEDIA
    }

    /** Seek-up is the previous-track key on this Nissan wheel; seek-down is next-track. */
    fun adjustedPercent(current: Int, seekUp: Boolean): Int =
        (current + if (seekUp) -5 else 5).coerceIn(0, 100)
}

/** One long/repeated OEM key press must not sweep a relative volume slider. */
internal class NissanSeekVolumeGate {
    private var lastOperation = -1
    private var lastTarget: NissanSeekAudioRouting.Target? = null
    private var lastAtMs = -1L

    @Synchronized fun claim(operation: Int, target: NissanSeekAudioRouting.Target, atMs: Long): Boolean {
        if (operation == lastOperation && target == lastTarget && lastAtMs >= 0 &&
            atMs >= lastAtMs && atMs - lastAtMs < 250L) return false
        lastOperation = operation
        lastTarget = target
        lastAtMs = atMs
        return true
    }
}
