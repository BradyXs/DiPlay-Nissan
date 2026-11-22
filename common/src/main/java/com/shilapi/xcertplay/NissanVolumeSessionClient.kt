package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Optional P350 session-state bridge. Capability 1 is observation only, never key routing. */
internal object NissanVolumeSessionClient {
    private const val SERVER = "com.hsae.auto.IBINDER_SERVER"
    private const val SERVER_DESC = "com.hsae.autosdk.server.IServer"
    private const val SESSION_DESC = "com.hsae.core.volume.IVolumeSession.v1"
    private const val SERVER_GET_SESSION = 4
    private const val QUERY = 1
    private const val PUBLISH = 2
    private const val RELEASE = 3
    private const val API = 1
    private const val CAP_STATE = 1
    private const val CAP_ROUTING = 2
    private const val CALLBACK_DESC = "com.hsae.core.volume.IVolumeCallback.v1"
    @Volatile private var routingActive = false
    fun physicalRoutingActive(): Boolean = routingActive
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "p350-volume-session").apply { isDaemon = true }
    }
    @Volatile private var generation = 0L
    private var service: IBinder? = null // worker thread only
    private var token: IBinder? = null
    private var sequence = 0L
    private var registered = false
    private var lastChannel = -1
    private var nextProbeMs = 0L
    private var logger: ((String) -> Unit)? = null

    @Synchronized fun start(context: Context, controller: CarPlayController, log: (String) -> Unit) {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val id = ++generation
        worker.execute {
            clearWorker()
            if (generation != id) return@execute
            logger = log
            token = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(CALLBACK_DESC)
                    if (Binder.getCallingUid() != Process.SYSTEM_UID) return false
                    val channel = data.readInt()
                    val offset = data.readInt()
                    if (generation != id || offset !in -5..5 || offset == 0) return true
                    val current = CarPlayBackgroundSession.snapshot()
                    if (current?.controller !== controller || !CarPlayBackgroundSession.active) return true
                    val expected = when (channel) { 2 -> "navigation"; 3 -> "call"; 4 -> "assistant"; else -> return true }
                    if (current.sink.nissanActiveAudioChannel() != expected) return true
                    synchronized(this@NissanVolumeSessionClient) {
                        val target = if (channel == 3) NissanSeekAudioRouting.Target.CALL else NissanSeekAudioRouting.Target.NAVIGATION
                        val before = if (channel == 3) AirPlayPersistence.loadCallVolumePercent(context)
                            else AirPlayPersistence.loadNavigationVolumePercent(context)
                        val after = (before + offset * 5).coerceIn(0, 100)
                        if (channel == 3) AirPlayPersistence.saveCallVolumePercent(context, after)
                        else AirPlayPersistence.saveNavigationVolumePercent(context, after)
                        current.sink.updateRelativeVolumes(AirPlayPersistence.loadMediaVolumePercent(context),
                            AirPlayPersistence.loadNavigationVolumePercent(context), AirPlayPersistence.loadCallVolumePercent(context))
                        NissanCarPlayVolumeOverlay.show(context, target, after)
                        log("physical channel=$channel before=$before after=$after")
                    }
                    return true
                }
            }
            tick(id, controller)
        }
    }

    @Synchronized fun stop() {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val id = ++generation
        routingActive = false
        worker.execute { if (generation == id) clearWorker() }
    }

    /** Audio callbacks enqueue immediate changes; heartbeat still renews the firmware lease. */
    fun onAudioChannelChanged(channel: String) {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val id = generation
        worker.execute {
            if (generation == id) {
                val current = CarPlayBackgroundSession.snapshot()?.controller ?: return@execute
                publishState(id, current, channel)
            }
        }
    }

    private fun tick(id: Long, controller: CarPlayController) {
        publishState(id, controller, null)
        if (generation == id) worker.schedule({ tick(id, controller) }, 1, TimeUnit.SECONDS)
    }

    private fun publishState(id: Long, controller: CarPlayController, eventChannel: String?) {
        if (generation != id) return
        val snapshot = CarPlayBackgroundSession.snapshot()
        val active = snapshot?.controller === controller && CarPlayBackgroundSession.active &&
            controller.activeAirPlaySessionToken() != null
        if (!active) {
            releaseWorker()
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            if (service == null && now >= nextProbeMs) {
                service = acquire()
                nextProbeMs = now + 10_000L
                if (service != null) log("state bridge ready; physical routing unavailable")
            }
            val channel = when (eventChannel ?: snapshot?.sink?.nissanActiveAudioChannel()) {
                "media" -> 1
                "navigation" -> 2
                "call" -> 3
                "assistant" -> 4
                else -> 0
            }
            if (service != null) {
                val result = call(PUBLISH) { data ->
                    data.writeLong(sequence++)
                    data.writeInt(channel)
                }
                if (result == 0) {
                    registered = true
                    routingActive = true
                    if (channel != lastChannel) log("session channel=$channel")
                    lastChannel = channel
                } else {
                    log("state publish failed status=$result")
                    service = null
                    registered = false
                    routingActive = false
                }
            }
        }
    }

    private fun acquire(): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        val server = manager.getMethod("getService", String::class.java).invoke(null, SERVER) as? IBinder
            ?: return null
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        val candidate: IBinder
        try {
            request.writeInterfaceToken(SERVER_DESC)
            if (!server.transact(SERVER_GET_SESSION, request, response, 0)) return null
            response.readException()
            candidate = response.readStrongBinder() ?: return null
        } finally { request.recycle(); response.recycle() }
        val query = Parcel.obtain()
        val result = Parcel.obtain()
        try {
            query.writeInterfaceToken(SESSION_DESC)
            if (!candidate.transact(QUERY, query, result, 0)) return null
            result.readException()
            if (result.readInt() != 0 || result.readInt() != API || result.readInt() and (CAP_STATE or CAP_ROUTING) != (CAP_STATE or CAP_ROUTING)) return null
            candidate
        } finally { query.recycle(); result.recycle() }
    }.getOrNull()

    private fun call(operation: Int, write: (Parcel) -> Unit = {}): Int = runCatching {
        val binder = service ?: return -1
        val owner = token ?: return -1
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(SESSION_DESC)
            request.writeInt(API)
            request.writeStrongBinder(owner)
            write(request)
            if (!binder.transact(operation, request, response, 0)) return -1
            response.readException()
            response.readInt()
        } finally { request.recycle(); response.recycle() }
    }.getOrDefault(-1)

    private fun releaseWorker() {
        if (registered) {
            val status = call(RELEASE)
            log("session released status=$status")
        }
        registered = false
        routingActive = false
        lastChannel = -1
    }

    private fun clearWorker() {
        releaseWorker()
        service = null
        token = null
        sequence = 0L
        nextProbeMs = 0L
        logger = null
    }
    private fun log(message: String) { logger?.invoke("P350VolumeSession: $message") }
}
