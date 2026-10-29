package com.shilapi.xcertplay.airplay

/** Monotonic liveness marker for received AirPlay payloads, independent of iAP2 chatter. */
internal object AirPlayMediaActivity {
    private const val RECENT_WINDOW_NS = 15_000_000_000L

    @Volatile private var lastPayloadNs = 0L
    @Volatile internal var clock: () -> Long = System::nanoTime

    fun onPayload() { lastPayloadNs = clock() }

    fun recent(): Boolean {
        val last = lastPayloadNs
        val age = clock() - last
        return last != 0L && age >= 0 && age < RECENT_WINDOW_NS
    }

    internal fun resetForTest() { lastPayloadNs = 0L }
}
