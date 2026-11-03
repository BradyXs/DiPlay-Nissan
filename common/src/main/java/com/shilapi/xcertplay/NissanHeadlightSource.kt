package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/** Reads the OEM lamp signal without registering a vehicle-control callback. */
internal class NissanHeadlightSource(
    context: Context,
    private val report: (String) -> Unit,
) : CarPlayNightModeController.HeadlightSource {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var receiverRegistered = false
    private var generation = 0
    private var diagnosticAvailable = false
    private var broadcastNight: Boolean? = null
    private var lastDescription: String? = null
    private var listener: ((Boolean?) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION) return
            val extras = intent.extras ?: return
            val primary = extras.get(EXTRA_NIGHT)
            val alternate = extras.get("day_night_mode")
            val value = when (primary) {
                is Boolean -> primary
                else -> when (alternate) {
                    is Boolean -> alternate
                    is Int -> if (alternate == 0 || alternate == 1) alternate == 1 else null
                    else -> null
                }
            } ?: return
            broadcastNight = value
            if (!diagnosticAvailable) publish(value, "broadcast")
        }
    }

    override fun start(onNight: (Boolean?) -> Unit) {
        stop()
        listener = onNight
        val run = ++generation
        try {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
            receiverRegistered = true
        } catch (error: Exception) {
            report("Headlights: broadcast registration failed error=${error.javaClass.simpleName}")
        }
        val worker = HandlerThread("nissan-headlight-query").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        handler.post(object : Runnable {
            override fun run() {
                val result = readLights()
                main.post {
                    if (generation != run || listener == null) return@post
                    diagnosticAvailable = result != null
                    val value = result?.let { it != 0 } ?: broadcastNight
                    publish(value, if (result != null) "diagnose lights=$result"
                        else if (value != null) "broadcast" else "unavailable")
                }
                handler.postDelayed(this, POLL_MS)
            }
        })
    }

    override fun stop() {
        generation++
        listener = null
        diagnosticAvailable = false
        broadcastNight = null
        lastDescription = null
        if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
        receiverRegistered = false
        thread?.quit()
        thread = null
    }

    private fun publish(night: Boolean?, source: String) {
        val description = "source=$source night=${night ?: "unknown"}"
        if (lastDescription != description) {
            lastDescription = description
            report("Headlights: $description")
        }
        listener?.invoke(night)
    }

    private fun readLights(): Int? {
        val binder = try {
            val manager = Class.forName("android.os.ServiceManager")
            manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
        } catch (_: Exception) { null } ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!binder.transact(1, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() == 0) return null
            reply.readInt() // speed
            reply.readInt().takeIf { it >= 0 }
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
        const val ACTION = "com.hsae.auto.ACTION_DAY_NIGHT_MODE"
        const val EXTRA_NIGHT = "com.hsae.auto.EXTRA_BE_AT_NIGHT"
        const val POLL_MS = 2_000L
    }
}
