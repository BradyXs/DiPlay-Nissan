package com.shilapi.xcertplay.media

/** The iPhone also labels non-navigation sounds as "default". Require route and duck signals. */
internal class NissanGuidanceAudio(private val windowMillis: Long = 5_000L) {
    @Volatile private var lastUpdateMillis = -1L
    @Volatile private var airPlayDuckActive = false

    fun onRouteGuidanceUpdate(nowMillis: Long) {
        lastUpdateMillis = nowMillis
    }

    fun onAirPlayDuckingChanged(active: Boolean) { airPlayDuckActive = active }

    fun duckRequested(): Boolean = airPlayDuckActive

    fun isGuidance(audioType: String, channel: AudioChannel, nowMillis: Long,
                   confirmedStream: Boolean = false): Boolean {
        if (channel != AudioChannel.NAVIGATION) return false
        if (!audioType.equals("default", ignoreCase = true)) return true
        if (airPlayDuckActive && confirmedStream) return true
        val last = lastUpdateMillis
        return airPlayDuckActive && last >= 0 && nowMillis >= last && nowMillis - last <= windowMillis
    }

    fun updateAgeMillis(nowMillis: Long): Long? =
        lastUpdateMillis.takeIf { it >= 0 && nowMillis >= it }?.let { nowMillis - it }
}
