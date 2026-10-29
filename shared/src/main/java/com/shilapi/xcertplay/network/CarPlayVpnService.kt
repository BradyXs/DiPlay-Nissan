package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlaySocketBinder
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    @Volatile private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    @Volatile var lastAttachFailure: String? = null
        private set
    /**
     * Set once a per-app VPN establish fails on a genuine API 21+ ROM: its netd cannot
     * do uid fwmark rules. Later attaches go straight to the route-only VPN instead of
     * hitting the same rejection every retry. (On 4.4-framework ROMs such as the
     * Allwinner T3 the framework adds the UID rule itself, so this memo does not apply;
     * see withStaleUidRuleHint.)
     */
    @Volatile private var perAppVpnUnsupported = false

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        lastAttachFailure = null
        var attachStage = "VPN configuration"
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val builder = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
            if (realSdkInt() >= 21 && !perAppVpnUnsupported) {
                // Per-app VPN scopes CarPlay traffic to this app on genuine API 21+ ROMs.
                // Use the real API level, not the faked SDK_INT: on 4.4-framework ROMs
                // (e.g. Allwinner T3 reporting 27) the per-app attempt makes the
                // framework issue a UID fwmark rule that the real 4.4 netd rejects
                // ("Invalid argument"). Skip it and go straight to route-only.
                builder.setBlocking(true).addAllowedApplication(packageName)
            }
            // KitKat has no per-app VPN (and neither do ROMs whose netd rejects uid rules).
            // Only the IPv6 link-local route above is installed; no default route, DNS,
            // Wi-Fi internet or mobile internet is captured.
            attachStage = "VPN establish"
            val tunFd = try {
                builder.establish()
            } catch (error: Exception) {
                if (!perAppVpnUnsupported && isUidRuleUnsupported(error)) {
                    if (realSdkInt() >= 21) {
                        // Genuine API 21+: our per-app attempt triggered the rejection;
                        // retry without it.
                        // Remember: this ROM's netd cannot do per-app VPN; go straight to
                        // route-only next time instead of hitting the same rejection every retry.
                        perAppVpnUnsupported = true
                        runCatching {
                            listener.onDebugLog("wired per-app VPN UID rule rejected by ROM netd: ${attachFailure(error)}; trying link-local route-only VPN")
                        }
                        val fallbackBuilder = Builder()
                            .addAddress(linkLocal, LINK_PREFIX)
                            .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                            .setSession(SESSION_NAME)
                            .setMtu(TUN_MTU)
                        fallbackBuilder.setBlocking(true)
                        attachStage = "VPN establish (route-only fallback after UID rule failure)"
                        try {
                            fallbackBuilder.establish()
                        } catch (fallbackError: Exception) {
                            fallbackError.addSuppressed(error)
                            runCatching {
                                listener.onDebugLog("wired route-only VPN also rejected: ${attachFailure(fallbackError)}; original per-app error: ${attachFailure(error)}")
                            }
                            throw fallbackError
                        }
                    } else {
                        // Issue #127: 4.4-framework ROMs (real API 19, e.g. Allwinner T3,
                        // whether or not they fake SDK_INT) add the UID fwmark rule in the
                        // framework's own establish(), so a route-only re-establish would
                        // hit the same leaked rule. Surface the reboot recovery instead
                        // of retrying the doomed establish.
                        throw withStaleUidRuleHint(error)
                    }
                } else {
                    // Issue #79: some vendor ROMs (e.g. Freescale/HSAE NTDA3 on 4.4.3) wedge
                    // their netd VPN state after a reconnect: every establish() then fails
                    // inside the framework at "interface fwmark rule add tunX" with
                    // "No such device". The app cannot repair framework netd state, so
                    // translate the cryptic netd line into an actionable hint.
                    throw withWedgedVpnHint(error)
                }
            } ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            runCatching { listener.onDebugLog("wired VPN TUN established address=$linkLocal mtu=$TUN_MTU") }
            if (realSdkInt() < Build.VERSION_CODES.LOLLIPOP) LegacyTunBlocking.enable(tunFd)

            val ipv6Bridge = Ipv6NcmBridge(
                ncm = ncm,
                tun = tunFd,
                hostMac = hostMac,
                hostLinkLocal = linkLocal,
                onDebugLog = { line -> runCatching { listener.onDebugLog(line) } },
                onError = { error -> onTransportError(generation, listener, error) },
            )
            attachStage = "NCM IPv6 bridge startup"
            ipv6Bridge.start()
            bridge = ipv6Bridge
            runCatching { listener.onDebugLog("wired NCM IPv6 bridge started") }

            attachStage = "AirPlay listener bind"
            // Issue #133: on some vendor kernels the iPhone's TCP SYN reaches the TUN but the
            // kernel never answers (no SYN-ACK, no RST). A listener bound to a scopeless
            // link-local address is fragile there: inbound packets carry the TUN interface's
            // scope while the socket has none, so the lookup may never match. Scope the bind
            // address to the TUN interface when it can be resolved (same remedy family as
            // #124). This must run after establish(): only then does the kernel own the
            // address on an interface. Falls back to the scopeless address when the scope
            // cannot be resolved, preserving today's behavior.
            val scopedAddress = AirPlaySocketBinder.scopedLinkLocal(address)
            startAirPlayServer(
                generation,
                AirPlayAttachment(scopedAddress, config, identity, pairings, mfi, listener, media),
            )
            runCatching { listener.onDebugLog("wired AirPlay listener bound port=${boundPort()}") }
            boundPort()?.let { port ->
                runCatching { listener.onDebugLog("wired airplay listen kernel-check: ${verifyTcp6Listen(port)}") }
            }
            AttachResult.Started
        } catch (error: Exception) {
            lastAttachFailure = "$attachStage: ${attachFailure(error)}"
            Log.e(TAG, "wired VPN/NCM attach failed: $lastAttachFailure", error)
            runCatching { listener.onDebugLog("wired VPN/NCM attach failed: $lastAttachFailure") }
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    fun sendUnsolicitedNeighborAdvertisement(force: Boolean = false) {
        bridge?.sendUnsolicitedNeighborAdvertisement(force)
    }

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    /**
     * Issue #133: the iPhone's TCP SYN reached the TUN on a T3 head unit but the kernel never
     * emitted SYN-ACK or RST. Java's bind() succeeding does not prove the kernel registered the
     * listener (wedged netd/VPN state can lie, see #79/#127). /proc/net/tcp6 is world-readable:
     * confirm a LISTEN socket actually exists for our port so the next diagnostic report can
     * distinguish "listener never registered" from "SYN lost between TUN ingress and TCP".
     */
    private fun verifyTcp6Listen(port: Int): String {
        val portHex = port.toString(16).uppercase().padStart(4, '0')
        return try {
            var found = false
            java.io.File("/proc/net/tcp6").bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    // Columns: sl | local_address | rem_address | st | ...
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size >= 4 &&
                        fields[1].endsWith(":$portHex", ignoreCase = true) &&
                        fields[3].equals("0A", ignoreCase = true)
                    ) {
                        found = true
                        break
                    }
                    line = reader.readLine()
                }
            }
            if (found) {
                "kernel LISTEN present on tcp6 port $port"
            } else {
                "WARNING: no LISTEN socket for port $port in /proc/net/tcp6 " +
                    "(bind succeeded but the kernel never registered it; reboot the head unit)"
            }
        } catch (error: Exception) {
            "/proc/net/tcp6 unreadable: ${error.message}"
        }
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Preserve the cause and throwing frame when older ROMs report only "Invalid argument". */
        internal fun attachFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") {
                "${it.javaClass.simpleName}: ${it.message ?: "no message"}"
            }
            val origin = error.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
            return chain + origin
        }

        internal fun isUidRuleUnsupported(error: Throwable): Boolean {
            return generateSequence(error) { it.cause }.take(8).any { cause ->
                val message = cause.message.orEmpty()
                message.contains("uid rule", ignoreCase = true) ||
                    message.contains("fwmark", ignoreCase = true) ||
                    message.contains("Failed to add uid", ignoreCase = true)
            }
        }

        /**
         * Issue #79: translate the framework-internal netd failure
         * "interface fwmark rule add tunX ... No such device" into an actionable hint.
         * Retrying the identical establish() cannot recover wedged netd VPN state, so the
         * original error is preserved and a reboot/reinstall recovery note is appended.
         */
        internal fun withWedgedVpnHint(error: Throwable): Throwable {
            val wedged = generateSequence(error) { it.cause }.take(8).any { cause ->
                val message = cause.message.orEmpty()
                message.contains("fwmark rule add", ignoreCase = true) &&
                    message.contains("No such device", ignoreCase = true)
            }
            if (!wedged) {
                // Issue #137: some 4.4.x ROMs (e.g. Hangsheng on 4.4.3) have a broken
                // VPN framework service: establish() dies inside Parcel.readException
                // with a NullPointerException. The app cannot repair the system VPN
                // service; translate into an actionable hint.
                val brokenVpnService = generateSequence(error) { it.cause }.take(8).any { cause ->
                    cause is NullPointerException &&
                        cause.stackTrace.any { it.className == "android.os.Parcel" &&
                            it.methodName == "readException" }
                }
                if (!brokenVpnService) return error
                return IOException(
                    "VPN establish failed: the head unit's system VPN service is broken " +
                        "(NullPointerException inside Parcel.readException); the ROM's VPN " +
                        "framework cannot create the TUN interface. Reboot the head unit and " +
                        "retry; if it persists, the ROM's VPN service is unusable and wired " +
                        "CarPlay cannot work on this ROM. " +
                        "Original error: ${error.message}",
                    error,
                )
            }
            return IOException(
                "VPN establish failed: the system netd rejected its own fwmark rule for the " +
                    "TUN interface (\"No such device\"); the head unit's VPN state is wedged " +
                    "and retrying cannot clear it. Reboot the head unit and retry; " +
                    "reinstalling the app also clears it. " +
                    "Original error: ${error.message}",
                error,
            )
        }
        /**
         * Issue #127: translate a rejected "interface fwmark uid add" on 4.4-framework
         * ROMs (real API 19, e.g. Allwinner T3) into an actionable hint. The framework
         * adds the UID rule inside its own establish(), and this ROM's netd rejects
         * re-adding it ("Invalid argument") once a previous session's rule leaked past
         * graceful teardown, so every in-process re-establish hits the same rejection.
         * Issue #129: the same stale-state class surfaces as "Device or resource busy"
         * on "interface fwmark rule add" when a conflicting rule is still present.
         * The app cannot clear netd state; only a head-unit reboot (or app process
         * restart, which lets the framework tear the VPN down fully) recovers it.
         */
        internal fun withStaleUidRuleHint(error: Throwable): Throwable {
            // Quote what netd actually said instead of hardcoding one variant.
            val detail = generateSequence(error) { it.cause }.take(8)
                .mapNotNull { cause ->
                    val message = cause.message.orEmpty()
                    when {
                        message.contains("Device or resource busy", ignoreCase = true) ->
                            "Device or resource busy"
                        message.contains("Invalid argument", ignoreCase = true) ->
                            "Invalid argument"
                        else -> null
                    }
                }.firstOrNull() ?: "rejected"
            return IOException(
                "VPN establish failed: the head unit's netd rejected the framework's fwmark " +
                    "routing rule (\"$detail\"); a previous session's rule was not cleaned up " +
                    "and re-adding it always fails. The app cannot clear netd state: reboot " +
                    "the head unit and retry (restarting the app may also recover it). " +
                    "Original error: ${error.message}",
                error,
            )
        }

        /**
         * Returns the real API level; delegates to the shared [com.shilapi.xcertplay.compat.realSdkInt].
         * Kept for call-site compatibility.
         */
        internal fun realSdkInt(): Int = com.shilapi.xcertplay.compat.realSdkInt()

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
