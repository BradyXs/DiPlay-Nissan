package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Coordinates CarPlay microphone streams with the Nissan firmware's wake-word recorder. */
internal class NissanVoiceWakeCoordinator(
    private val context: Context,
    private val report: (String) -> Unit,
) : Closeable {
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null
    private var restoreWake = false
    private var restoreGeneration = 0

    @Synchronized fun pauseForMicrophone(): Boolean {
        // A previous capture's asynchronous wake restart may still own this binding.
        // Re-query the OEM service for every capture rather than assuming it remains paused.
        restoreGeneration++
        if (connection != null) releaseBinding()
        val ready = CountDownLatch(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection === this) binder = service
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) binder = null
                ready.countDown()
                report("Microphone: Nissan voice service disconnected")
            }
        }
        val intent = Intent(VOICE_ACTION).setComponent(ComponentName(VOICE_PACKAGE, VOICE_SERVICE))
        val bound = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            report("Microphone: Nissan voice bind failed error=${error.javaClass.simpleName}")
            false
        }
        if (!bound) {
            report("Microphone: Nissan voice service unavailable; capture disabled")
            return false
        }
        connection = serviceConnection
        val connected = try {
            ready.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && binder != null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!connected) {
            report("Microphone: Nissan voice bind timed out; capture disabled")
            releaseBinding()
            return false
        }
        return try {
            val service = binder ?: return false
            val wasWakeActive = queryWake(service)
            report("Microphone: Nissan voice wake beforeCapture=$wasWakeActive")
            if (!wasWakeActive) {
                report("Microphone: Nissan voice wake already inactive; starting microphone")
                return true
            }
            restoreWake = true
            transact(service, TRANSACTION_STOP_WAKE, oneway = false)
            val wakeStillActive = queryWake(service)
            report("Microphone: Nissan voice wake paused afterCaptureRequest=$wakeStillActive")
            if (wakeStillActive) {
                report("Microphone: Nissan voice wake remains active; capture disabled")
                restoreAfterMicrophone()
                return false
            }
            // The OEM binder returns before its native capture thread releases the PCM device.
            Thread.sleep(WAKE_RELEASE_MILLIS)
            true
        } catch (error: Exception) {
            report("Microphone: Nissan voice pause failed error=${error.javaClass.simpleName}; capture disabled")
            restoreAfterMicrophone()
            false
        }
    }

    @Synchronized fun restoreAfterMicrophone() {
        val service = binder
        if (service != null && restoreWake) {
            try {
                transact(service, TRANSACTION_RESTART_WAKE, oneway = true)
                report("Microphone: Nissan voice wake restore requested")
                val generation = ++restoreGeneration
                restoreWake = false
                Thread({
                    try {
                        Thread.sleep(RESTORE_CHECK_DELAY_MILLIS)
                        synchronized(this) {
                            if (generation != restoreGeneration) return@Thread
                            try {
                                report("Microphone: Nissan voice wake restored afterCapture=${queryWake(service)}")
                            } catch (error: Exception) {
                                report("Microphone: Nissan voice wake restore check failed error=${error.javaClass.simpleName}")
                            } finally {
                                releaseBinding()
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }, "nissan-voice-wake-check").apply { isDaemon = true; start() }
                return
            } catch (error: Exception) {
                report("Microphone: Nissan voice wake restore failed error=${error.javaClass.simpleName}")
            }
        }
        restoreGeneration++
        restoreWake = false
        releaseBinding()
    }

    override fun close() = restoreAfterMicrophone()

    private fun queryWake(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!service.transact(TRANSACTION_IS_WAKE_STARTED, data, reply, 0)) {
                throw RemoteException("Nissan wake query unsupported")
            }
            reply.readException()
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transact(service: IBinder, code: Int, oneway: Boolean) {
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!service.transact(code, data, reply, if (oneway) IBinder.FLAG_ONEWAY else 0)) {
                throw RemoteException("Nissan voice transaction $code unsupported")
            }
            reply?.readException()
        } finally {
            reply?.recycle()
            data.recycle()
        }
    }

    private fun releaseBinding() {
        connection?.let { bound ->
            runCatching { context.unbindService(bound) }
        }
        connection = null
        binder = null
    }

    private companion object {
        const val VOICE_PACKAGE = "com.hsae.voice.adapter"
        const val VOICE_SERVICE = "com.hsae.voice.adapter.service.VoiceManage"
        const val VOICE_ACTION = "com.hsae.auto.VOICE_SERVICE"
        const val DESCRIPTOR = "com.hsae.autosdk.voice.IVoiceManager"
        const val TRANSACTION_RESTART_WAKE = 9
        const val TRANSACTION_IS_WAKE_STARTED = 14
        const val TRANSACTION_STOP_WAKE = 16
        const val BIND_TIMEOUT_MILLIS = 1_500L
        const val WAKE_RELEASE_MILLIS = 200L
        const val RESTORE_CHECK_DELAY_MILLIS = 350L
    }
}
