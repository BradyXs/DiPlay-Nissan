package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android 4.4 - 8 system-generated Wi-Fi Direct group owner for wireless CarPlay. */
@android.annotation.SuppressLint("MissingPermission")
internal class LegacyWifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IllegalStateException("WifiP2pManager is unavailable")

    private var thread: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private var created = false
    @Volatile private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyWifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0)
        checkPrerequisites()

        val worker = HandlerThread("xcertplay-wifi-p2p-legacy").apply { start() }
        thread = worker
        val p2pChannel = manager.initialize(
            appContext,
            worker.looper,
            WifiP2pManager.ChannelListener {
                diagnostic("Legacy Wi-Fi Direct channel disconnected")
            },
        )
        channel = p2pChannel

        try {
            requestGroupInfo(p2pChannel, 2_000)?.let { group ->
                diagnostic(
                    "Legacy Wi-Fi Direct removing retained group owner=${group.isGroupOwner} " +
                        "name=${group.networkName ?: "unknown"}",
                )
                removeGroupBlocking(p2pChannel, 4_000)
            }

            val createLatch = CountDownLatch(1)
            val createFailure = AtomicReference<IOException?>()
            manager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    created = true
                    createLatch.countDown()
                }

                override fun onFailure(reason: Int) {
                    createFailure.set(
                        IOException("Legacy Wi-Fi Direct createGroup failed code=$reason"),
                    )
                    createLatch.countDown()
                }
            })
            if (!await(createLatch, minOf(timeoutMillis, 10_000))) {
                throw IOException("Legacy Wi-Fi Direct group creation timed out")
            }
            createFailure.get()?.let { throw it }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var lastReason = "group information unavailable"
            while (!closed && System.nanoTime() < deadline) {
                val group = requestGroupInfo(p2pChannel, 1_500)
                if (group == null) {
                    lastReason = "group information unavailable"
                    continue
                }
                if (!group.isGroupOwner) {
                    throw IOException("Legacy Wi-Fi Direct became a group client instead of owner")
                }

                val ssid = group.networkName?.takeIf { it.isNotBlank() }
                val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
                val connectionAddress = requestConnectionInfo(p2pChannel, 1_500)
                val networkInterface = interfaceName?.let {
                    runCatching { NetworkInterface.getByName(it) }.getOrNull()
                }
                val hostAddress = networkInterface?.let { iface ->
                    wirelessHostAddress(
                        addresses = Collections.list(iface.inetAddresses),
                        interfaceIndex = iface.index,
                        preferIpv4 = true,
                    )
                } ?: connectionAddress

                val frameworkFrequency = readFrameworkFrequency(group)
                val driverFrequency = interfaceName?.let { name ->
                    LegacyHotspotRadio.read(name, null).also { reading ->
                        diagnostic(
                            "Legacy Wi-Fi Direct radio iface=$name " +
                                "frequency=${reading.frequencyMHz ?: "unknown"} " +
                                "error=${reading.error ?: "none"}",
                        )
                    }.frequencyMHz
                }
                val frequency = frameworkFrequency ?: driverFrequency
                val channelNumber = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0

                if (ssid == null || passphrase == null || hostAddress == null) {
                    lastReason =
                        "incomplete group ssid=${ssid != null} passphrase=${passphrase != null} " +
                            "host=${hostAddress != null} iface=${interfaceName ?: "unknown"}"
                    Thread.sleep(150)
                    continue
                }

                val bandLabel = when (frequency) {
                    in 2412..2484 -> "2.4 GHz"
                    in 5160..5895 -> "5 GHz"
                    in 5955..7115 -> "6 GHz"
                    else -> "system selected"
                }
                diagnostic(
                    "Legacy Wi-Fi Direct ready iface=${interfaceName ?: "unknown"} " +
                        "band=$bandLabel channel=$channelNumber frequency=${frequency ?: "unknown"}",
                )

                return WirelessHotspotInfo(
                    ssid = ssid,
                    passphrase = passphrase,
                    security = Iap2WirelessSecurity.WPA_WPA2,
                    channel = channelNumber,
                    frequencyMHz = frequency,
                    bssid = networkInterface?.hardwareAddress
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                        ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                    interfaceName = interfaceName,
                    hostAddress = hostAddress,
                    bandLabel = bandLabel,
                    backend = WirelessHotspotBackend.WIFI_P2P,
                )
            }
            throw IOException("Legacy Wi-Fi Direct timed out: $lastReason")
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        val activeChannel = channel
        if (created && activeChannel != null) {
            runCatching { removeGroupBlocking(activeChannel, 2_000) }
        }
        channel = null
        created = false
        activeChannel?.let { ch ->
            runCatching { ch.javaClass.getMethod("close").invoke(ch) }
        }
        thread?.quitSafely()
        thread = null
    }

    private fun checkPrerequisites() {
        if (
            Build.VERSION.SDK_INT >= 23 && appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("Allow precise Location for DiPlay before using Wi-Fi Direct")
        }
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled == false) {
            throw IOException("Turn on Wi-Fi in the head unit settings before using Wi-Fi Direct")
        }
    }
    private fun requestGroupInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pGroup?>()
        manager.requestGroupInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) return null
        return result.get()
    }

    private fun requestConnectionInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ): InetAddress? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pInfo?>()
        manager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) return null
        val info = result.get() ?: return null
        if (!info.groupFormed || !info.isGroupOwner) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }
    private fun removeGroupBlocking(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ) {
        val latch = CountDownLatch(1)
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(reason: Int) {
                diagnostic("Legacy Wi-Fi Direct removeGroup failed code=$reason")
                latch.countDown()
            }
        })
        await(latch, timeoutMillis)
    }

    private fun readFrameworkFrequency(group: WifiP2pGroup): Int? = runCatching {
        (group.javaClass.getMethod("getFrequency").invoke(group) as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
    }.getOrNull()

    private fun await(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for legacy Wi-Fi Direct", interrupted)
    }
}
