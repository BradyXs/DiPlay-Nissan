package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings

/** Learns a 15–30 fps main-screen ceiling for the next session. AirPlay negotiates it per session. */
class NissanVideoFrameRate {
    data class Profile(
        val wireless: Boolean,
        val width: Int,
        val height: Int,
        val hevc: Boolean,
        val softwareDecoder: Boolean,
    )

    data class PlaybackWindow(
        val inputFps: Double,
        val decodedFraction: Double,
        val shownFraction: Double,
        val queueP90Millis: Int,
        val maxArrivalGapMillis: Long,
        val recoveries: Int,
        val visibleThroughout: Boolean,
        val decodeP90Millis: Int = -1,
        val inputWaitP90Millis: Int = -1,
    )

    private class ProfileState {
        var nextFps = AirPlayDisplaySettings.MIN_ADAPTIVE_FPS
        var generation = 0L
        var cooldownAfterDownshift = false
    }

    private val profiles = LinkedHashMap<Profile, ProfileState>(16, 0.75f, true)

    inner class Session internal constructor(
        private val profile: Profile,
        private val generation: Long,
        val fps: Int,
    ) {
        private var consecutive = 0

        fun observe(window: PlaybackWindow): Int? = synchronized(this@NissanVideoFrameRate) {
            val state = profiles[profile] ?: return@synchronized null
            if (state.generation != generation || state.nextFps != fps) return@synchronized null

            // Static screens and missing surfaces do not demonstrate decoder load. Keep this
            // cadence requirement for promotion only: an overloaded decoder often makes its own
            // input cadence irregular, so requiring a healthy cadence here hides the overload.
            val continuous = window.visibleThroughout && window.inputFps >= fps * 0.8 &&
                window.maxArrivalGapMillis in 1..250
            val rateIndex = adaptiveRates.indexOf(fps).takeIf { it >= 0 } ?: return@synchronized null
            val canStepDown = rateIndex > 0
            val severeDecoderOverload = canStepDown && window.visibleThroughout && (
                window.decodeP90Millis >= SEVERE_DECODE_P90_MILLIS ||
                    window.queueP90Millis >= SEVERE_QUEUE_P90_MILLIS ||
                    window.recoveries >= SEVERE_RECOVERY_COUNT ||
                    window.inputWaitP90Millis >= SEVERE_INPUT_WAIT_P90_MILLIS
                )
            if (severeDecoderOverload) {
                consecutive = 0
                state.cooldownAfterDownshift = true
                state.nextFps = adaptiveRates[rateIndex - 1]
                return@synchronized state.nextFps
            }

            val moderateDecoderOverload = canStepDown && window.visibleThroughout && (
                window.queueP90Millis >= OVERLOAD_QUEUE_P90_MILLIS ||
                    window.recoveries >= OVERLOAD_RECOVERY_COUNT ||
                    ((window.decodeP90Millis >= OVERLOAD_DECODE_P90_MILLIS ||
                        window.inputWaitP90Millis >= OVERLOAD_INPUT_WAIT_P90_MILLIS) &&
                        (window.decodedFraction < 0.85 || window.shownFraction < 0.85))
                )
            if (moderateDecoderOverload) {
                consecutive++
                if (consecutive < OVERLOAD_WINDOWS_REQUIRED) return@synchronized null
                consecutive = 0
                state.cooldownAfterDownshift = true
                state.nextFps = adaptiveRates[rateIndex - 1]
                return@synchronized state.nextFps
            }
            consecutive = 0

            val targetFps = adaptiveRates.getOrNull(rateIndex + 1) ?: return@synchronized null
            val qualifies = continuous &&
                window.decodeP90Millis in 0..maxDecodeP90ForPromotion(targetFps) &&
                window.inputWaitP90Millis in 0..MAX_PROMOTION_INPUT_WAIT_P90_MILLIS &&
                window.decodedFraction >= 0.92 && window.shownFraction >= 0.90 &&
                window.queueP90Millis in 0..12 && window.recoveries == 0
            consecutive = if (qualifies) consecutive + 1 else 0
            val required = if (state.cooldownAfterDownshift) 6 else 3
            if (consecutive < required) return@synchronized null
            state.nextFps = targetFps
            state.cooldownAfterDownshift = false
            state.nextFps
        }
    }

    @Synchronized
    fun begin(profile: Profile): Session {
        val state = profiles.getOrPut(profile) { ProfileState() }
        if (profiles.size > 12) profiles.remove(profiles.keys.first())
        state.generation = ++generation
        return Session(profile, state.generation, state.nextFps)
    }

    private var generation = 0L

    private companion object {
        const val MIN_ADAPTIVE_FPS = AirPlayDisplaySettings.MIN_ADAPTIVE_FPS
        const val MAX_ADAPTIVE_FPS = AirPlayDisplaySettings.MIN_FPS
        val adaptiveRates = AirPlayDisplaySettings.ADAPTIVE_FPS_STEPS
        const val MAX_PROMOTION_INPUT_WAIT_P90_MILLIS = 20
        const val OVERLOAD_QUEUE_P90_MILLIS = 80
        const val SEVERE_QUEUE_P90_MILLIS = 150
        const val OVERLOAD_DECODE_P90_MILLIS = 180
        const val SEVERE_DECODE_P90_MILLIS = 400
        const val OVERLOAD_INPUT_WAIT_P90_MILLIS = 80
        const val SEVERE_INPUT_WAIT_P90_MILLIS = 250
        const val OVERLOAD_RECOVERY_COUNT = 2
        const val SEVERE_RECOVERY_COUNT = 4
        const val OVERLOAD_WINDOWS_REQUIRED = 2

        fun maxDecodeP90ForPromotion(targetFps: Int): Int = when (targetFps) {
            20 -> 250
            24 -> 180
            25 -> 120
            MAX_ADAPTIVE_FPS -> 80
            else -> 0
        }
    }
}
