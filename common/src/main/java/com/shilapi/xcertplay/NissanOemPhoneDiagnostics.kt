package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/** Read-only view of the OEM phone state used by HmiManager to route physical call keys. */
internal object NissanOemPhoneDiagnostics {
    private const val SERVICE = "com.hsae.auto.IBINDER_BT_PHONE_MANAGER"
    private const val DESCRIPTOR = "com.hsae.autosdk.bt.phone.IBTPhoneManager"
    private const val GET_PHONE_STATE = 5
    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val HEARTBEAT_MS = 15_000L

    @Volatile private var state = "not_started"
    @Volatile private var latest = "sample=unavailable"
    private var session: Session? = null

    @Synchronized fun start(log: SessionLogFile?) {
        session?.stop()
        state = "starting"
        latest = "sample=unavailable"
        session = Session(log).also(Session::start)
    }

    @Synchronized fun stop() {
        session?.stop()
        session = null
        state = "stopped"
    }

    fun summary(): String = "Nissan OEM phone: state=$state $latest"

    /** Fresh read for phone-key arbitration; null means the OEM phone service is unavailable. */
    fun currentPhoneState(): Int? = runCatching { readPhoneState(phoneService()) }.getOrNull()

    private class Session(private val log: SessionLogFile?) {
        @Volatile private var running = true
        private val thread = Thread(::run, "nissan-oem-phone-probe").apply { isDaemon = true }

        fun start() = thread.start()
        fun stop() { running = false; thread.interrupt() }

        private fun run() {
            var previous: String? = null
            var lastReportMs = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val sample = try {
                    val binder = phoneService()
                    state = "available"
                    val phoneState = readPhoneState(binder)
                    val radio = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled?.toString() ?: "absent" }
                        .getOrElse { "unknown:${it.javaClass.simpleName}" }
                    "btRadio=$radio phoneState=$phoneState CarPlayActive=${CarPlayBackgroundSession.active}"
                } catch (error: Exception) {
                    val reason = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
                    state = "unavailable:$reason"
                    "unavailable reason=$reason CarPlayActive=${CarPlayBackgroundSession.active}"
                }
                if (!running) return
                latest = sample
                if (sample != previous || now - lastReportMs >= HEARTBEAT_MS) {
                    AsyncDiagnosticLog.append(log, "OEM phone: $sample")
                    lastReportMs = now
                }
                previous = sample
                try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun phoneService(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        return manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
            ?: throw IllegalStateException("phone_service_missing")
    }

    private fun readPhoneState(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            check(binder.transact(GET_PHONE_STATE, data, reply, 0)) { "phone_transaction_5" }
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
