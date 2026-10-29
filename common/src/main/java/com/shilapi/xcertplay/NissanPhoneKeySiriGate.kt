package com.shilapi.xcertplay

/** Ignore duplicate OEM phone-key reports while one Siri request is being prepared. */
internal class NissanPhoneKeySiriGate(private val minimumGapMs: Long = 1_200L) {
    private var controller: Any? = null
    private var lastPressMs = -1L

    @Synchronized fun claim(candidate: Any, elapsedMs: Long): Boolean {
        if (controller === candidate && lastPressMs >= 0L &&
            elapsedMs >= lastPressMs && elapsedMs - lastPressMs < minimumGapMs) return false
        controller = candidate
        lastPressMs = elapsedMs
        return true
    }

    @Synchronized fun release(candidate: Any, elapsedMs: Long) {
        if (controller === candidate && lastPressMs == elapsedMs) {
            controller = null
            lastPressMs = -1L
        }
    }
}
