package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Versioned interface supplied by the platform-signed head-unit component in custom firmware. */
internal class NissanFirmwareBridgeClient(private val context: Context, private val report: (String) -> Unit) {
    enum class Result { ACQUIRED, UNAVAILABLE, FAILED }

    private val token = Binder()
    private var connection: ServiceConnection? = null
    private var service: IBinder? = null
    private var acquired = false

    @Synchronized fun acquireVoice(): Result {
        if (acquired) return Result.ACQUIRED
        if (connection != null) releaseVoice()
        val component = ComponentName(PACKAGE, SERVICE)
        val present = runCatching { context.packageManager.getServiceInfo(component, 0) }.isSuccess
        if (!present) return Result.UNAVAILABLE
        val ready = CountDownLatch(1)
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection === this) service = binder
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) service = null
                ready.countDown()
                report("Microphone: Nissan firmware bridge disconnected")
            }
        }
        connection = next
        return try {
            val bound = context.bindService(Intent().setComponent(component), next, Context.BIND_AUTO_CREATE)
            if (!bound) {
                releaseVoice()
                Result.UNAVAILABLE
            } else if (!ready.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                report("Microphone: Nissan firmware bridge bind timeout")
                releaseVoice()
                Result.FAILED
            } else {
                val binder = service ?: throw IllegalStateException("firmware bridge missing binder")
                val info = call(binder, TX_INFO) { reply -> reply.readInt() to reply.readInt() }
                if (info.first != VERSION || info.second and CAP_VOICE_LEASE == 0) {
                    throw IllegalStateException("firmware bridge version or capability mismatch")
                }
                val result = call(binder, TX_VOICE_ACQUIRE, { it.writeStrongBinder(token) }) { it.readInt() }
                if (result == 1) {
                    acquired = true
                    report("Microphone: Nissan firmware voice lease acquired version=$VERSION")
                    Result.ACQUIRED
                } else {
                    report("Microphone: Nissan firmware voice lease unavailable result=$result")
                    releaseVoice()
                    Result.FAILED
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report("Microphone: Nissan firmware bridge failed error=${error.javaClass.simpleName}")
            releaseVoice()
            Result.FAILED
        }
    }

    @Synchronized fun releaseVoice() {
        if (acquired) {
            service?.let { binder ->
                runCatching {
                    call(binder, TX_VOICE_RELEASE, { it.writeStrongBinder(token) }) { it.readInt() }
                }.onFailure { report("Microphone: Nissan firmware voice release failed error=${it.javaClass.simpleName}") }
            }
        }
        acquired = false
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    private fun <T> call(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "firmware bridge transaction unsupported: $code" }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val PACKAGE = "com.bradyxu.headunitmanager"
        const val SERVICE = "com.bradyxu.headunitmanager.FirmwareBridgeService"
        const val DESCRIPTOR = "com.bradyxu.headunitmanager.IFirmwareBridge.v1"
        const val VERSION = 1
        const val CAP_VOICE_LEASE = 1
        const val TX_INFO = 1
        const val TX_VOICE_ACQUIRE = 2
        const val TX_VOICE_RELEASE = 3
        const val BIND_TIMEOUT_MS = 1_500L
    }
}
