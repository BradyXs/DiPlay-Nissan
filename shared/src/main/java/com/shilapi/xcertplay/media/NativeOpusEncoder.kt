package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/** NDK libopus fallback for legacy head units with no MediaCodec Opus encoder. */
internal class NativeOpusEncoder private constructor(private var handle: Long) : Closeable {
    val available: Boolean get() = handle != 0L

    @Synchronized fun encode(pcm: ByteArray): List<ByteArray> {
        if (!available) return emptyList()
        return runCatching { nativeEncode(handle, pcm)?.let(::listOf) ?: emptyList() }
            .onFailure { Log.w(TAG, "Native Opus encode failed", it) }
            .getOrDefault(emptyList())
    }

    @Synchronized override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeDestroy(old)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val DEFAULT_COMPLEXITY = 3
        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("opus_microphone"); true }
                .onFailure { Log.w(TAG, "Software Opus library unavailable", it) }
                .getOrDefault(false)
        }

        fun isAvailable(): Boolean = create(48_000)?.use { it.available } == true

        fun create(bitrate: Int): NativeOpusEncoder? {
            if (!loaded) return null
            val handle = runCatching { nativeCreate(bitrate.coerceIn(6_000, 128_000), DEFAULT_COMPLEXITY) }
                .onFailure { Log.w(TAG, "Software Opus initialization failed", it) }
                .getOrDefault(0L)
            return if (handle != 0L) NativeOpusEncoder(handle) else null
        }

        @JvmStatic private external fun nativeCreate(bitrate: Int, complexity: Int): Long
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ByteArray): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
