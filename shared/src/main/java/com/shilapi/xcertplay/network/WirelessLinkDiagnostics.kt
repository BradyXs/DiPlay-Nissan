package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/** Read-only Wi-Fi radio/link snapshot for the periodic wireless session report. */
@Suppress("DEPRECATION", "MissingPermission")
internal class WirelessLinkDiagnostics(
    context: Context,
    private val hotspot: WirelessHotspotInfo,
) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun snapshot(): String {
        val enabled = runCatching { wifi?.isWifiEnabled }.getOrNull()
        val station = runCatching { wifi?.connectionInfo }.getOrNull()
        val rssi = station?.rssi?.takeIf { it in MIN_VALID_RSSI..MAX_VALID_RSSI }
        val signalLevel = rssi?.let { value ->
            runCatching { WifiManager.calculateSignalLevel(value, SIGNAL_LEVELS) }.getOrNull()
        }
        val linkSpeed = station?.linkSpeed?.takeIf { it > 0 }
        val stationFrequency = stationFrequencyMHz(station)
        val activeNetwork = runCatching {
            connectivity?.activeNetworkInfo?.let { "${it.typeName}:${it.state}" } ?: "none"
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val supplicant = station?.supplicantState?.name ?: "unavailable"

        val driverRadio = if (Build.VERSION.SDK_INT <= LEGACY_DRIVER_MAX_API) {
            hotspot.interfaceName?.let { LegacyHotspotRadio.read(it, hotspot.bandLabel) }
        } else {
            null
        }
        val liveFrequency = driverRadio?.frequencyMHz
        val apFrequency = liveFrequency ?: hotspot.frequencyMHz
        val apChannel = apFrequency?.let(::wifiFrequencyMhzToChannel) ?: hotspot.channel
        val apFrequencySource = when {
            liveFrequency != null -> "driver"
            hotspot.frequencyMHz != null -> "startup"
            else -> "unknown"
        }

        return "wirelessLink backend=${hotspot.backend.name} wifiEnabled=${enabled ?: "unknown"} " +
            "activeNetwork=$activeNetwork supplicant=$supplicant " +
            "stationRssiDbm=${rssi ?: "unknown"} stationSignalLevel5=${signalLevel ?: "unknown"} " +
            "stationLinkSpeedMbps=${linkSpeed ?: "unknown"} " +
            "stationFrequencyMHz=${stationFrequency ?: "api_unavailable"} " +
            "apBand=${hotspot.bandLabel} apChannel=$apChannel apFrequencyMHz=${apFrequency ?: "unknown"} " +
            "apFrequencySource=$apFrequencySource peerRssi=not_exposed_by_android_api"
    }

    private fun stationFrequencyMHz(info: WifiInfo?): Int? {
        if (info == null || Build.VERSION.SDK_INT < STATION_FREQUENCY_API) return null
        // getFrequency was added after Android 4.4; reflection keeps it out of the API 19 verifier.
        return runCatching {
            (info.javaClass.getMethod("getFrequency").invoke(info) as? Number)?.toInt()
        }.getOrNull()?.takeIf { it > 0 }
    }

    private companion object {
        const val MIN_VALID_RSSI = -126
        const val MAX_VALID_RSSI = 0
        const val SIGNAL_LEVELS = 5
        const val STATION_FREQUENCY_API = 21
        const val LEGACY_DRIVER_MAX_API = 28
    }
}
