package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reports a live wired session only. Authorization and gate maintenance belong to firmware. */
internal class NissanHfpSessionClient(
    private val context: Context,
    private val snapshot: () -> Pair<Any, String>?,
    private val report: (String) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nissan-firmware-session").apply { isDaemon = true }
    }
    private var current: Registration? = null // Accessed only by worker.

    private inner class Registration(val key: Any, val address: String) {
        val token = Binder()
        @Volatile var binder: IBinder? = null
        @Volatile var retired = false
        var bound = false // Main thread only.
        var lastResult: Int? = null
        var checkedBinder: IBinder? = null
        var supported = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!retired && !closed.get()) binder = service
            }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
    }

    init {
        worker.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { tick() }.onFailure {
                report("Nissan firmware session heartbeat failed error=${it.javaClass.simpleName}")
                retire()
            }
        }, 0, 3, TimeUnit.SECONDS)
    }

    private fun tick() {
        val live = snapshot()
        if (live == null) { retire(); return }
        if (current?.key !== live.first || !live.second.equals(current?.address, ignoreCase = true)) {
            retire()
            val registration = Registration(live.first, live.second)
            current = registration
            main.post {
                if (!registration.retired && !closed.get()) {
                    registration.bound = runCatching {
                        context.bindService(Intent().setComponent(ComponentName(
                            "com.bradyxu.headunitmanager",
                            "com.bradyxu.headunitmanager.FirmwareBridgeService",
                        )), registration.connection, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)
                }
            }
            return
        }
        val registration = current ?: return
        val binder = registration.binder ?: return
        if (registration.checkedBinder !== binder) {
            registration.supported = transaction(binder, 1) { reply ->
                reply.readInt() == 1 && reply.readInt() and 8 != 0
            }
            registration.checkedBinder = binder
        }
        if (!registration.supported) return // Stock / older firmware remains unchanged.
        // Recheck live identity immediately before sending a pulse from this worker.
        val latest = snapshot() ?: run { retire(); return }
        if (closed.get() || latest.first !== registration.key ||
            !latest.second.equals(registration.address, ignoreCase = true)) { retire(); return }
        val result = transaction(binder, 7, {
            it.writeStrongBinder(registration.token)
            it.writeString(registration.address)
            it.writeInt(1) // Wired only; no wireless handoff behavior is enabled here.
        }) { it.readInt() }
        if (registration.lastResult != result) {
            report("Nissan firmware session result=$result (1=registered,2=manual_hold)")
            registration.lastResult = result
        }
    }

    private fun retire() {
        val registration = current ?: return
        current = null
        registration.retired = true
        registration.binder?.let { binder ->
            if (registration.supported) runCatching {
                transaction(binder, 8, { it.writeStrongBinder(registration.token) }) { it.readInt() }
            }
        }
        main.post {
            if (registration.bound) runCatching { context.unbindService(registration.connection) }
            registration.bound = false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute { retire() }
        worker.shutdown()
    }

    private fun <T> transaction(
        binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.bradyxu.headunitmanager.IFirmwareBridge.v1")
            write(data)
            check(binder.transact(code, data, reply, 0)) { "unsupported firmware transaction" }
            reply.readException()
            return read(reply)
        } finally { reply.recycle(); data.recycle() }
    }
}
