package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayCallPhase
import java.io.File

/** The factory HMI starts its own voice UI after reporting the physical answer key. */
internal object NissanOemVoiceUi {
    private const val SERVICE = "com.hsae.auto.IBINDER_VOICE_MANAGER"
    private const val DESCRIPTOR = "com.hsae.autosdk.voice.IVoiceManager"
    private const val IS_VR_STARTED = 8
    private const val HIDE_VR = 13
    private const val STOP_SR_VIEW = 18

    /** The OEM HMI starts VR after reporting the key, so close that recognition before invoking Siri. */
    fun requestSiriAfterPhoneKey(context: Context, controller: CarPlayController, onFailed: () -> Unit) {
        val app = context.applicationContext
        Thread({
            val log = SessionLogFile(File(File(app.filesDir, "logs"), "diplay.log"))
            try { Thread.sleep(350L) } catch (_: InterruptedException) { onFailed(); return@Thread }
            if (!siriSessionActive(controller)) { onFailed(); return@Thread }
            val service = voiceService()
            val vrBefore = service?.let { runCatching { isVrStarted(it) }.getOrNull() }
            val stopped = if (vrBefore == true) service?.let { runCatching { stopSrView(it) }.getOrDefault(false) } else false
            AsyncDiagnosticLog.append(log,
                "OEM voice: pre-Siri vrStarted=${vrBefore ?: "unknown"} stopSrRequested=$stopped serviceAvailable=${service != null}")
            if (stopped == true) {
                try { Thread.sleep(200L) } catch (_: InterruptedException) { onFailed(); return@Thread }
            }
            if (!siriSessionActive(controller)) { onFailed(); return@Thread }
            val sent = controller.requestSiri()
            AsyncDiagnosticLog.append(log, "Siri: OEM phone key request queued=$sent")
            if (!sent) { onFailed(); return@Thread }
            // hideVR is rate-limited by the OEM for one second after startVR.
            try { Thread.sleep(900L) } catch (_: InterruptedException) { return@Thread }
            if (!siriSessionActive(controller)) return@Thread
            val vrAfter = service?.let { runCatching { isVrStarted(it) }.getOrNull() }
            val hidden = if (vrAfter == true) service?.let { runCatching { hideVr(it) }.getOrDefault(false) } else false
            AsyncDiagnosticLog.append(log,
                "OEM voice: post-Siri vrStarted=${vrAfter ?: "unknown"} hideRequested=$hidden")
        }, "nissan-phone-key-siri").apply { isDaemon = true; start() }
    }

    private fun siriSessionActive(controller: CarPlayController): Boolean =
        CarPlayBackgroundSession.active &&
            CarPlayBackgroundSession.snapshot()?.controller === controller &&
            controller.activeAirPlaySessionToken() != null &&
            controller.currentCallSnapshot() == null

    fun hideAfterCarPlayAnswer(context: Context, controller: CarPlayController, epoch: Long) {
        val app = context.applicationContext
        Thread({
            val log = SessionLogFile(File(File(app.filesDir, "logs"), "diplay.log"))
            var hideRequests = 0
            for (delayMs in longArrayOf(200L, 500L, 700L)) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                if (CarPlayBackgroundSession.snapshot()?.controller !== controller) return@Thread
                val call = controller.currentCallSnapshot()
                if (call?.epoch != epoch ||
                    (call.phase != CarPlayCallPhase.RINGING && call.phase != CarPlayCallPhase.ACTIVE)) {
                    AsyncDiagnosticLog.append(log,
                        "OEM voice: post-answer epoch=$epoch skipped=call_changed phase=${call?.phase}")
                    return@Thread
                }
                val service = voiceService() ?: continue
                val query = runCatching { isVrStarted(service) }
                query.exceptionOrNull()?.let {
                    AsyncDiagnosticLog.append(log,
                        "OEM voice: post-answer epoch=$epoch queryFailed=${it.javaClass.simpleName}")
                }
                val started = query.getOrNull() ?: continue
                if (!started) continue
                val hidden = runCatching { hideVr(service) }.getOrDefault(false)
                if (hidden) hideRequests++
                AsyncDiagnosticLog.append(log,
                    "OEM voice: post-answer epoch=$epoch vrStarted=true hideRequested=$hidden")
            }
            AsyncDiagnosticLog.append(log, "OEM voice: post-answer epoch=$epoch hideRequests=$hideRequests checksComplete=true")
        }, "nissan-post-answer-voice").apply { isDaemon = true; start() }
    }

    private fun voiceService(): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    }.getOrNull()

    private fun isVrStarted(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            check(service.transact(IS_VR_STARTED, data, reply, 0)) { "Nissan voice query unavailable" }
            reply.readException()
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun hideVr(service: IBinder): Boolean {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            return service.transact(HIDE_VR, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    private fun stopSrView(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!service.transact(STOP_SR_VIEW, data, reply, 0)) return false
            reply.readException()
            return true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
