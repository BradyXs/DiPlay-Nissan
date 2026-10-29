package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/** Read-only observations of the HSAE source service on the Nissan Android 4.4 head unit. */
internal object NissanOemSourceDiagnostics {
    private const val SERVICE = "com.hsae.auto.IBINDER_SOURCE"
    private const val DESCRIPTOR = "com.hsae.autosdk.source.ISource"
    // Verified against this firmware's autosdk.jar: ISource.Stub transactions 1 and 20.
    private const val GET_CURRENT_SOURCE = 1
    private const val GET_CURRENT_AUDIO_FOCUS = 20
    private const val SAMPLE_INTERVAL_MS = 1_000L
    private const val HEARTBEAT_MS = 10_000L

    private data class Sample(val source: Int, val focus: Int, val carPlayActive: Boolean, val atMs: Long) {
        fun describe() = "source=${source.label()}($source) focus=${focus.label()}($focus) " +
            "carPlayActive=$carPlayActive uptimeMs=$atMs"
    }

    @Volatile private var latest: Sample? = null
    @Volatile private var state = "not_started"
    private var session: Session? = null

    @Synchronized
    fun start(log: SessionLogFile?) {
        session?.stop()
        latest = null
        state = "starting"
        session = Session(log).also(Session::start)
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
        state = "stopped"
    }

    fun summary(): String = "Nissan OEM source: state=$state ${latest?.describe() ?: "sample=unavailable"}"

    private class Session(private val log: SessionLogFile?) {
        @Volatile private var running = true
        private val thread = Thread(::run, "nissan-oem-source-probe").apply { isDaemon = true }
        fun start() = thread.start()
        fun stop() { running = false; thread.interrupt() }

        private fun run() {
            var previous: Sample? = null
            var previousError: String? = null
            var lastReportMs = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                try {
                    val binder = sourceService()
                    val sample = Sample(readInt(binder, GET_CURRENT_SOURCE),
                        readInt(binder, GET_CURRENT_AUDIO_FOCUS), CarPlayBackgroundSession.active, now)
                    if (!running) return
                    latest = sample
                    state = "available"
                    if (previous == null || previous.source != sample.source || previous.focus != sample.focus ||
                        previous.carPlayActive != sample.carPlayActive || now - lastReportMs >= HEARTBEAT_MS) {
                        AsyncDiagnosticLog.append(log, "OEM source: ${sample.describe()}")
                        lastReportMs = now
                    }
                    previous = sample
                    previousError = null
                } catch (error: Exception) {
                    if (!running) return
                    val reason = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
                    state = "unavailable:$reason"
                    if (reason != previousError || now - lastReportMs >= HEARTBEAT_MS) {
                        AsyncDiagnosticLog.append(log, "OEM source: unavailable reason=$reason")
                        lastReportMs = now
                    }
                    previousError = reason
                }
                try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun sourceService(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        return manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
            ?: throw IllegalStateException("source_service_missing")
    }

    private fun readInt(binder: IBinder, transaction: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            check(binder.transact(transaction, data, reply, 0)) { "transaction_$transaction unavailable" }
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun Int.label(): String = OEM_SOURCE_NAMES.getOrNull(this) ?: "UNKNOWN"

    private val OEM_SOURCE_NAMES = arrayOf(
        "EMPTY", "RADIO", "USB_MUSIC", "USB_VIDEO", "BT_MUSIC", "BT_PHONE", "IPOD_MUSIC",
        "SETTING", "CARLIFE_MUSIC", "CARPLAY_MUSIC", "AIR", "VOICE", "USER_BEHAVIOR",
        "REVERSE", "VOICE_REMINDER", "KUWO_MUSIC", "KAOLA_FM", "ONLINE_MUSIC", "V_CAN",
        "CARLIFE_VR", "CARLIFE_PHONE", "CARPLAY_SIRI", "CARPLAY_PHONE", "RADIO_DIAGNOSE",
        "AUDIO_DIAGNOSE", "ANDROID_BT", "NAVI", "LAUNCHER", "AUTO_CORE",
    )
}
