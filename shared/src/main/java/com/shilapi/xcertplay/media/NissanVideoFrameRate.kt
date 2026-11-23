package com.shilapi.xcertplay.media

/** Learns a main-screen FPS ceiling for the next session. AirPlay negotiates it once per session. */
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
        var nextFps = 30
        var generation = 0L
        var cooldownAfter60 = false
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
            val continuous = window.visibleThroughout && window.inputFps >= 24 &&
                window.maxArrivalGapMillis in 1..250
            val qualifies = continuous && if (fps == 30) {
                window.decodedFraction >= 0.92 && window.shownFraction >= 0.90 &&
                    window.queueP90Millis in 0..12 && window.recoveries == 0
            } else {
                window.queueP90Millis >= 100 || (window.queueP90Millis >= 40 &&
                    (window.decodedFraction < 0.85 || window.shownFraction < 0.85 || window.recoveries > 0))
            }
            consecutive = if (qualifies) consecutive + 1 else 0
            val required = if (fps == 60) 2 else if (state.cooldownAfter60) 6 else 3
            if (consecutive < required) return@synchronized null
            if (fps == 60) state.cooldownAfter60 = true
            state.nextFps = if (fps == 30) 60 else 30
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
}
