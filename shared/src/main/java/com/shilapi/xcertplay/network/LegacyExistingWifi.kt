package com.shilapi.xcertplay.network

import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** KitKat station-network adapter; it never changes Wi-Fi or the default route. */
internal class LegacyExistingWifi(
    private val connectivity: ConnectivityManager,
    private val wifi: WifiManager,
    private val ssid: String,
    private val passphrase: String,
    private val log: (String) -> Unit,
    private val onChanged: () -> Unit,
) : Closeable {
    @Volatile private var closed = false
    private var monitor: Thread? = null

    private fun stationAddress(): InetAddress? {
        if (connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected != true) return null
        val info = wifi.connectionInfo ?: return null
        val live = info.ssid?.removeSurrounding("\"")
        if (!live.isNullOrEmpty() && live != WifiManager.UNKNOWN_SSID && live != ssid) {
            throw IOException("Saved Wi-Fi name does not match the connected Wi-Fi")
        }
        val ip = info.ipAddress
        return if (ip == 0) null else InetAddress.getByAddress(ByteArray(4) { (ip ushr (8 * it)).toByte() })
    }

    fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!closed && System.nanoTime() < deadline) {
            val address = stationAddress()
            val iface = address?.let { target -> Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { it.isUp && !it.isLoopback && Collections.list(it.inetAddresses).contains(target) } }
            if (address != null && iface != null) {
                val addresses = existingWifiHostAddresses(Collections.list(iface.inetAddresses), iface.index)
                log("Existing Wi-Fi attached legacy iface=${iface.name} host=${address.hostAddress} channel=auto")
                monitor = Thread({
                    try {
                        while (!closed) {
                            Thread.sleep(1000)
                            if (!closed && runCatching { stationAddress() }.getOrNull() != address) {
                                onChanged()
                                return@Thread
                            }
                        }
                    } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }, "diplay-legacy-wifi").apply { isDaemon = true; start() }
                return WirelessHotspotInfo(ssid, passphrase,
                    if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
                    0, null, null, iface.name, address, "Auto", WirelessHotspotBackend.EXISTING_WIFI,
                    hostAddresses = addresses)
            }
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi is unavailable or startup was cancelled")
    }

    override fun close() { closed = true; monitor?.interrupt() }
}
