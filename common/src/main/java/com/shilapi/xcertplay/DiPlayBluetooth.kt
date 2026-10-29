package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.bluetooth.BluetoothAddressResolver
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager
import java.util.concurrent.atomic.AtomicBoolean

internal object DiPlayBluetooth {
    private const val CACHE_NAME = "nissan_bluetooth_address"
    private const val CACHE_KEY = "local_address"
    private val prefetchInFlight = AtomicBoolean(false)

    /** AirPlay configuration is built on the UI thread; never bind the OEM service here. */
    fun localAddress(context: Context): String? {
        val custom = BluetoothAddressResolver.normalize(AirPlayPersistence.loadCustomBluetoothMac(context))
        if (custom != null) return custom
        if (!com.shilapi.xcertplay.host.BuildConfig.NISSAN_ANDROID_44_PROFILE) {
            return BluetoothAddressResolver.resolve(context)
        }
        return BluetoothAddressResolver.normalize(
            context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE).getString(CACHE_KEY, null),
        )
    }

    /** Warm the OEM address while the host prepares its other connection prerequisites. */
    @android.annotation.SuppressLint("MissingPermission")
    fun prefetchLocalAddress(context: Context, report: (String) -> Unit = {}) {
        if (!com.shilapi.xcertplay.host.BuildConfig.NISSAN_ANDROID_44_PROFILE ||
            localAddress(context) != null || !prefetchInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            val startedAtNs = SystemClock.elapsedRealtimeNanos()
            try {
                val address = runCatching {
                    CarPlayBluetoothManager.get(appContext).hsae.localAddress(appContext)
                }.getOrNull()?.let(BluetoothAddressResolver::normalize)
                if (address != null) {
                    appContext.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
                        .edit().putString(CACHE_KEY, address).apply()
                }
                report(
                    "UI_STARTUP stage=nissan-bt-address-cache " +
                        "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L} " +
                        "result=${if (address == null) "unavailable" else "ready"} thread=background",
                )
            } finally {
                prefetchInFlight.set(false)
            }
        }, "niplay-bt-address-cache").apply { isDaemon = true; start() }
    }
}
