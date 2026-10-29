package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors

/** The factory navigation amplifier level, independent of NiPlay's per-track gain. */
internal object NissanOemNavigationVolume {
    const val MAX_LEVEL = 11
    private const val SERVICE = "com.hsae.auto.IBINDER_SETTINGS"
    private const val DESCRIPTOR = "com.hsae.autosdk.settings.IAutoSettings"
    private const val NAVIGATION_TYPE = 10
    private const val SET_SYSTEM_VOLUME = 31
    private const val GET_SYSTEM_VOLUME = 32
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nissan-navigation-volume").apply { isDaemon = true }
    }

    fun readAsync(done: (Int?, String?) -> Unit) {
        worker.execute {
            val result = runCatching { readLevel(settingsService()) }
            done(result.getOrNull(), result.exceptionOrNull()?.javaClass?.simpleName)
        }
    }

    fun setAsync(level: Int, done: (Int?, String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val service = settingsService()
                setLevel(service, level)
                readLevel(service)
            }
            done(result.getOrNull(), result.exceptionOrNull()?.javaClass?.simpleName)
        }
    }

    internal fun readLevel(service: IBinder): Int = transact(service, GET_SYSTEM_VOLUME) {
        writeInt(NAVIGATION_TYPE)
    }

    internal fun setLevel(service: IBinder, level: Int) {
        require(level in 0..MAX_LEVEL)
        transact(service, SET_SYSTEM_VOLUME) {
            writeInt(NAVIGATION_TYPE)
            writeInt(level)
        }
    }

    private fun transact(service: IBinder, code: Int, writeArgs: Parcel.() -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeArgs()
            check(service.transact(code, data, reply, 0)) { "navigation_volume_transaction_$code" }
            reply.readException()
            return if (code == GET_SYSTEM_VOLUME) reply.readInt() else 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun settingsService(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        return manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
            ?: error("settings_service_missing")
    }
}
