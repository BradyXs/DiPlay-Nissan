package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Carries only raw key numbers from the OEM-bound service process to the active CarPlay process. */
internal object NissanOemKeyRelay {
    private const val ACTION = "com.shilapi.xcertplay.NISSAN_OEM_KEY"
    private const val OPERATION = "operation"
    private const val SENT_AT_MS = "sent_at_ms"
    private const val MAX_EVENT_AGE_MS = 1_500L
    private val userBehaviorReceived = AtomicInteger()

    fun summary(): String = "userBehaviorReceived=${userBehaviorReceived.get()}"

    fun userBehavior(context: Context, operation: Int) = send(context, operation)

    private fun send(context: Context, operation: Int) {
        context.sendBroadcast(Intent(ACTION).apply {
            component = ComponentName(context, NissanOemKeyReceiver::class.java)
            putExtra(OPERATION, operation)
            putExtra(SENT_AT_MS, SystemClock.elapsedRealtime())
        })
    }

    private fun runningService(context: Context, service: Class<*>) =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100)
            .firstOrNull { it.service.className == service.name && it.service.packageName == context.packageName }

    fun serviceStatus(context: Context, service: Class<*>): String = runCatching {
        @Suppress("DEPRECATION")
        val running = runningService(context, service)
        if (running == null) "not_running" else "process=${running.process} clients=${running.clientCount}"
    }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

    fun receive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val sentAtMs = intent.getLongExtra(SENT_AT_MS, -1L)
        val operation = intent.getIntExtra(OPERATION, -1)
        val ageMs = SystemClock.elapsedRealtime() - sentAtMs
        if (sentAtMs <= 0L || ageMs !in 0L..MAX_EVENT_AGE_MS) {
            AsyncDiagnosticLog.append(
                SessionLogFile(File(File(context.filesDir, "logs"), "diplay.log")),
                "OEM key relay: stale operation=$operation " +
                    "ageMs=$ageMs dropped=true",
            )
            return
        }
        userBehaviorReceived.incrementAndGet()
        NissanPhoneKeyProbe.onPhoneOperation(context, operation)
    }
}

class NissanOemKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = NissanOemKeyRelay.receive(context, intent)
}
