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
    )

    private class ProfileState {
        var nextFps = AirPlayDisplaySettings.MIN_ADAPTIVE_FPS
        var generation = 0L
        var cooldownAfter30 = false
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

            // Static screens, missing surfaces and irregular input do not demonstrate decoder load.
            val continuous = window.visibleThroughout && window.inputFps >= fps * 0.8 &&
                window.maxArrivalGapMillis in 1..250
            val qualifies = continuous && if (fps == MIN_ADAPTIVE_FPS) {
                window.decodedFraction >= 0.92 && window.shownFraction >= 0.90 &&
                    window.queueP90Millis in 0..12 && window.recoveries == 0
            } else {
                window.queueP90Millis >= 100 || (window.queueP90Millis >= 40 &&
                    (window.decodedFraction < 0.85 || window.shownFraction < 0.85 || window.recoveries > 0))
            }
            consecutive = if (qualifies) consecutive + 1 else 0
            val required = if (fps == MAX_ADAPTIVE_FPS) 2 else if (state.cooldownAfter30) 6 else 3
            if (consecutive < required) return@synchronized null
            if (fps == MAX_ADAPTIVE_FPS) state.cooldownAfter30 = true
            state.nextFps = if (fps == MIN_ADAPTIVE_FPS) MAX_ADAPTIVE_FPS else MIN_ADAPTIVE_FPS
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
    }
}
