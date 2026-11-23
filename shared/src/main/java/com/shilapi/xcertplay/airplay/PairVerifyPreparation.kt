package com.shilapi.xcertplay.airplay

/** One unused ephemeral key for this controller's first Pair Verify exchange. */
internal class PairVerifyPreparation {
    private var started = false
    private var consumedOrClosed = false
    private var prepared: AirPlayCrypto.X25519KeyPair? = null

    fun prepare(): Boolean {
        synchronized(this) {
            if (started || consumedOrClosed) return false
            started = true
        }
        val key = AirPlayCrypto.x25519Generate()
        synchronized(this) {
            if (consumedOrClosed) {
                key.privateKey.fill(0)
                return false
            }
            prepared = key
            return true
        }
    }

    @Synchronized
    fun take(): AirPlayCrypto.X25519KeyPair? {
        consumedOrClosed = true
        return prepared.also { prepared = null }
    }

    @Synchronized
    fun close() {
        take()?.privateKey?.fill(0)
    }
}
