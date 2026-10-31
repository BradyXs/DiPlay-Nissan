package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.host.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in callback for the P350 HMI firmware patch. The capability event is the handshake. */
internal object NissanFirmwareHmiKeys {
    private const val SERVICE = "com.hsae.auto.IBINDER_HMI"
    private const val HMI_DESCRIPTOR = "com.hsae.autosdk.hmi.IHmi"
    private const val CALLBACK_DESCRIPTOR = "com.hsae.autosdk.hmi.IHmiCallback"
    private const val CAPABILITY = 0x62ff
    private const val EVENT_BASE = 0x6200
    private const val RETRY_MS = 5_000L
    private val received = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0
    @Volatile private var worker: Thread? = null
    @Volatile private var service: IBinder? = null
    @Volatile private var active = false
    @Volatile private var status = "stopped"

    fun summary(): String = "firmwareHmi=$status active=${isActive()} received=${received.get()}"

    fun isActive(): Boolean = active && service?.isBinderAlive == true

    @Synchronized fun start(context: Context) {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE || worker?.isAlive == true) return
        val app = context.applicationContext ?: context
        val token = ++generation
        worker = Thread({ run(app, token) }, "nissan-firmware-hmi").also { it.start() }
    }

    @Synchronized fun stop() {
        ++generation
        active = false
        status = "stopped"
        worker?.interrupt()
        worker = null
    }

    private fun run(context: Context, token: Int) {
        while (generation == token && NissanPhoneKeyProbe.enabled(context)) {
            var binder: IBinder? = null
            var callback: IBinder? = null
            var death: IBinder.DeathRecipient? = null
            try {
                status = "looking_for_hmi"
                binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, SERVICE) as? IBinder
                if (binder == null || !binder.isBinderAlive) {
                    status = "hmi_unavailable"
                } else {
                    val currentBinder = binder
                    service = currentBinder
                    active = false
                    status = "registering"
                    callback = object : Binder() {
                        init { attachInterface(null, CALLBACK_DESCRIPTOR) }

                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code == INTERFACE_TRANSACTION) {
                                reply?.writeString(CALLBACK_DESCRIPTOR)
                                return true
                            }
                            if (code != 2) return super.onTransact(code, data, reply, flags)
                            data.enforceInterface(CALLBACK_DESCRIPTOR)
                            val key = data.readInt()
                            val down = data.readInt() != 0
                            if (key == CAPABILITY && down && generation == token) {
                                active = true
                                status = "registered"
                                record(context, "capability confirmed")
                            } else if (key in (EVENT_BASE + 6)..(EVENT_BASE + 32) && !down &&
                                generation == token && isActive()) {
                                val operation = key - EVENT_BASE
                                if (operation == 6 || operation == 7 || operation == 26 ||
                                    operation == 27 || operation == 28 ||
                                    operation == 29 || operation == 31 || operation == 32) {
                                    received.incrementAndGet()
                                    record(context, "event operation=$operation")
                                    main.post {
                                        if (generation == token && isActive()) {
                                            NissanPhoneKeyProbe.onFirmwareOperation(context, operation)
                                        }
                                    }
                                }
                            }
                            return true
                        }
                    }
                    death = IBinder.DeathRecipient {
                        active = false
                        status = "hmi_died"
                    }
                    currentBinder.linkToDeath(death, 0)
                    transactCallback(currentBinder, 1, callback)
                    val start = SystemClock.elapsedRealtime()
                    while (generation == token && currentBinder.isBinderAlive &&
                        (active || SystemClock.elapsedRealtime() - start < 2_000L)) {
                        Thread.sleep(250L)
                    }
                    if (!active && generation == token) status = "no_firmware_capability"
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                status = "registration_error:${error.javaClass.simpleName}"
                record(context, status)
            } finally {
                if (generation == token) active = false
                if (binder?.isBinderAlive == true && callback != null) {
                    runCatching { transactCallback(binder, 2, callback) }
                }
                if (binder != null && death != null) runCatching { binder.unlinkToDeath(death, 0) }
                if (generation == token && service === binder) service = null
            }
            if (generation != token || Thread.currentThread().isInterrupted) break
            try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
        }
        if (generation == token) status = "stopped"
    }

    private fun transactCallback(service: IBinder, code: Int, callback: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(HMI_DESCRIPTOR)
            data.writeStrongBinder(callback)
            check(service.transact(code, data, reply, 0)) { "HMI transaction $code unavailable" }
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun record(context: Context, message: String) {
        AsyncDiagnosticLog.append(
            SessionLogFile(File(File(context.filesDir, "logs"), "diplay.log")),
            "Firmware HMI keys: $message",
        )
    }
}
