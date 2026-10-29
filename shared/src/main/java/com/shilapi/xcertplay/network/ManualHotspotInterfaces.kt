package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

internal class ManualHotspotInterfaces(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val connectivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.getSystemService(ConnectivityManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    private val publicTethering = if (Build.VERSION.SDK_INT >= 36) PublicTethering(context) else null
    private var lastLegacyDiagnostic: String? = null

    fun sample(): HotspotNetworkSnapshot {
        val ap = (if (Build.VERSION.SDK_INT >= 36) publicTethering?.interfaces else null) ?: legacyApInterfaces()
        val before = runCatching { networkToken() }
        val upstreams = runCatching {
            checkNotNull(connectivity)
            if (Build.VERSION.SDK_INT < 21) legacyWifiUpstreams() else connectivity.allNetworks.mapNotNull { network ->
                val caps = checkNotNull(connectivity.getNetworkCapabilities(network))
                val links = checkNotNull(connectivity.getLinkProperties(network))
                links.interfaceName?.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            }.toSet()
        }.getOrNull()
        val defaultName = runCatching {
            if (Build.VERSION.SDK_INT >= 23) connectivity?.activeNetwork?.let { connectivity?.getLinkProperties(it)?.interfaceName }
            else if (connectivity?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI) upstreams?.singleOrNull() else null
        }.getOrNull()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { iface ->
                runCatching {
                    if (iface.isLoopback) null else HotspotInterfaceSnapshot(
                        iface.name, iface.index, iface.isUp, Collections.list(iface.inetAddresses),
                        wirelessInterfaceName(iface.name) || File("/sys/class/net/${iface.name}/wireless").isDirectory,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val after = runCatching { networkToken() }
        return HotspotNetworkSnapshot(
            interfaces, ap, upstreams, defaultName,
            consistent = before.isSuccess && after.isSuccess && before.getOrNull() == after.getOrNull(),
            // Legacy vendor frameworks can report a stale disabled state; interface ownership
            // and station-network exclusion still have to pass the selection policy below.
            apEnabled = CarHotspotStatus.isEnabled(context)?.takeUnless { !it && Build.VERSION.SDK_INT <= 28 },
        )
    }

    private fun networkToken(): Any? = if (Build.VERSION.SDK_INT >= 23) connectivity?.activeNetwork
        else connectivity?.activeNetworkInfo?.let { "${it.type}:${it.isConnected}:${it.extraInfo}" }

    private fun legacyWifiUpstreams(): Set<String> {
        if (connectivity?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected != true) return emptySet()
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wifi?.connectionInfo?.ipAddress ?: return emptySet()
        if (ip == 0) return emptySet()
        val address = java.net.InetAddress.getByAddress(ByteArray(4) { (ip ushr (8 * it)).toByte() })
        return Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
            Collections.list(it.inetAddresses).contains(address)
        }.map { it.name }.toSet()
    }

    // 旧平台只使用允许读取的结果；接口归属读不到时保持 unknown，不放宽普通网卡资格。
    @SuppressLint("PrivateApi")
    private fun legacyApInterfaces(): Set<String>? = runCatching {
        val manager = checkNotNull(connectivity)
        val tethered = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            .invoke(manager) as Array<*>
        val regexes = ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs")
            .invoke(manager) as Array<*>
        val names = tethered.filterIsInstance<String>()
        val patterns = regexes.filterIsInstance<String>()
        val ap = legacyHotspotInterfaces(names, patterns)
        val diagnostic = "legacy hotspot ownership=${if (ap == null) "unobservable" else "observed"} " +
            "tethered=$names wifiRegexes=$patterns matched=$ap"
        if (diagnostic != lastLegacyDiagnostic) {
            lastLegacyDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        ap
    }.getOrNull()

    override fun close() { if (Build.VERSION.SDK_INT >= 36) publicTethering?.close() }

    @RequiresApi(36)
    private class PublicTethering(context: Context) : Closeable {
        @Volatile var interfaces: Set<String>? = null
            private set
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                this@PublicTethering.interfaces = interfaces.filter { it.type == TetheringManager.TETHERING_WIFI }
                    .map { it.getInterface() }.toSet()
            }
        }
        private val registered = runCatching {
            checkNotNull(manager).registerTetheringEventCallback(context.mainExecutor, callback)
        }.isSuccess

        override fun close() {
            if (registered) runCatching { manager?.unregisterTetheringEventCallback(callback) }
        }
    }
}

internal fun legacyHotspotInterfaces(tethered: List<String>, wifiRegexes: List<String>): Set<String>? {
    if (wifiRegexes.isEmpty()) return null
    val patterns = wifiRegexes.map(::Regex)
    val matched = tethered.filter { name -> patterns.any { it.matches(name) } }.toSet()
    // 厂商可能动态使用 wlan0，而静态配置仍写 softap0；匹配失败不能证明没有热点。
    return matched.takeUnless { tethered.isNotEmpty() && it.isEmpty() }
}

internal fun wirelessInterfaceName(name: String): Boolean =
    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") ||
        name.contains("softap", ignoreCase = true) || name.startsWith("wifi") || name.startsWith("p2p")
