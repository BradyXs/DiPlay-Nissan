package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/** Polls the Nissan read-only vehicle-signal Binder while navigation is shown on the cluster. */
internal class NissanClusterVehicleSpeedSource(
    context: Context,
    private val onSpeedChanged: (Int?) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var generation = 0

    @Synchronized
    fun start() {
        if (thread != null) return
        val run = ++generation
        val nextThread = HandlerThread("nissan-cluster-speed").apply { start() }
        thread = nextThread
        val nextWorker = Handler(nextThread.looper)
        worker = nextWorker
        nextWorker.post(object : Runnable {
            private var lastDelivered: Int? = null
            private var invalidSamples = 0

            override fun run() {
                val speed = readSpeedKmh()
                var changed = false
                val next = if (speed != null) {
                    invalidSamples = 0
                    speed.also { if (it != lastDelivered) changed = true }
                } else {
                    invalidSamples++
                    if (invalidSamples >= INVALID_SAMPLES_TO_CLEAR && lastDelivered != null) {
                        changed = true
                        null
                    } else {
                        lastDelivered
                    }
                }
                if (changed) {
                    lastDelivered = next
                    main.post {
                        val current = synchronized(this@NissanClusterVehicleSpeedSource) {
                            generation == run && thread != null
                        }
                        if (current) onSpeedChanged(next)
                    }
                }
                synchronized(this@NissanClusterVehicleSpeedSource) {
                    if (generation == run && worker === nextWorker) {
                        nextWorker.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        })
    }

    @Synchronized
    fun stop() {
        generation++
        worker?.removeCallbacksAndMessages(null)
        worker = null
        thread?.quit()
        thread = null
    }

    private fun readSpeedKmh(): Int? {
        val binder = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            serviceManager.getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as? IBinder
        } catch (_: Exception) {
            null
        } ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!binder.transact(GET_VEHICLE_SIGNALS, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() == 0) return null
            // VehicleSignals parcels the integer speed first, followed by lights and other signals.
            // The OEM volume controller uses these same raw values as km/h thresholds.
            reply.readInt().takeIf { it in MIN_SPEED_KMH..MAX_SPEED_KMH }
        } catch (_: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val SERVICE = "com.hsae.auto.IBINDER_DIAGNOSE"
        const val DESCRIPTOR = "com.hsae.autosdk.diagnose.IDiagnose"
        const val GET_VEHICLE_SIGNALS = 1
        const val MIN_SPEED_KMH = 0
        const val MAX_SPEED_KMH = 300
        const val INVALID_SAMPLES_TO_CLEAR = 3
        const val POLL_INTERVAL_MS = 1_000L
    }
}
