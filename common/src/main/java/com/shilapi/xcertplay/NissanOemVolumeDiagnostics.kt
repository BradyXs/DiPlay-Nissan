package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** Read-only OEM volume callbacks, used to establish whether the physical knob can be observed. */
internal object NissanOemVolumeDiagnostics {
    private const val SERVICE = "com.hsae.auto.IBINDER_SETTINGS"
    private const val SETTINGS_INTERFACE = "com.hsae.autosdk.settings.IAutoSettings"
    private const val CALLBACK_INTERFACE = "com.hsae.autosdk.settings.IAutoSettingsCallback"
    private const val REGISTER = 29
    private const val UNREGISTER = 30
    private const val GET_SYSTEM_VOLUME = 32
    private const val GET_PHONE_VOLUME = 34

    @Volatile private var state = "not_started"
    @Volatile private var media = -1
    @Volatile private var navigation = -1
    @Volatile private var phone = -1
    private var session: Session? = null

    @Synchronized fun start(log: SessionLogFile?) {
        session?.stop()
        state = "starting"
        session = Session(log).also(Session::start)
    }

    @Synchronized fun stop() {
        session?.stop()
        session = null
        state = "stopped"
    }

    fun summary(): String = "Nissan OEM volume: state=$state media=$media/40 navigation=$navigation/11 phone=$phone/15 " +
        "CarPlayActive=${CarPlayBackgroundSession.active} CarPlayChannel=${activeChannel()}"

    private fun activeChannel(): String = CarPlayBackgroundSession.snapshot()?.sink?.nissanActiveAudioChannel() ?: "none"

    private class Session(private val log: SessionLogFile?) {
        @Volatile private var running = true
        private val callback = object : Binder() {
            init { attachInterface(null, CALLBACK_INTERFACE) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(CALLBACK_INTERFACE)
                    return true
                }
                if (code !in 1..10) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_INTERFACE)
                when (code) {
                    6 -> {
                        val type = data.readInt()
                        val value = data.readInt()
                        if (type == 1) media = value
                        if (type == 10) navigation = value
                        record("system type=$type value=$value CarPlayChannel=${activeChannel()}")
                    }
                    7 -> {
                        phone = data.readInt()
                        record("phone value=$phone CarPlayChannel=${activeChannel()}")
                    }
                    1, 5, 10 -> data.readInt()
                    2, 3, 4, 8, 9 -> data.readInt()
                }
                return true
            }
        }
        private val thread = Thread(::run, "nissan-oem-volume-probe").apply { isDaemon = true }

        fun start() = thread.start()
        fun stop() { running = false; thread.interrupt() }

        private fun run() {
            while (running) {
                var binder: IBinder? = null
                try {
                    val service = settingsService()
                    binder = service
                    register(service, REGISTER)
                    media = readVolume(service, GET_SYSTEM_VOLUME, 1)
                    navigation = readVolume(service, GET_SYSTEM_VOLUME, 10)
                    phone = readVolume(service, GET_PHONE_VOLUME)
                    state = "registered"
                    record("registered media=$media/40 navigation=$navigation/11 phone=$phone/15 " +
                        "CarPlayChannel=${activeChannel()}")
                    while (running && service.isBinderAlive) Thread.sleep(2_000L)
                } catch (error: Exception) {
                    val reason = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
                    if (state != "unavailable:$reason") record("unavailable reason=$reason")
                    state = "unavailable:$reason"
                } finally {
                    binder?.let { runCatching { register(it, UNREGISTER) } }
                }
                if (!running) break
                try { Thread.sleep(5_000L) } catch (_: InterruptedException) { break }
            }
        }

        private fun record(message: String) {
            if (running) AsyncDiagnosticLog.append(log, "OEM volume: $message")
        }

        private fun register(binder: IBinder, code: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SETTINGS_INTERFACE)
                data.writeStrongBinder(callback)
                check(binder.transact(code, data, reply, 0)) { "settings_transaction_$code" }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        private fun readVolume(binder: IBinder, code: Int, type: Int? = null): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SETTINGS_INTERFACE)
                if (type != null) data.writeInt(type)
                check(binder.transact(code, data, reply, 0)) { "settings_transaction_$code" }
                reply.readException()
                return reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun settingsService(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        return manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
            ?: error("settings_service_missing")
    }
}
