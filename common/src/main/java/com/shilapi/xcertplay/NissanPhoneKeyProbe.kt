package com.shilapi.xcertplay

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.orchestration.CarPlayCallPhase
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in relay for Nissan HMI key events reported through the user-behavior Binder. */
internal object NissanPhoneKeyProbe {
    private const val PREFS = "nissan_phone_key_probe"
    private const val ENABLED = "enabled"
    private const val ACTION = "com.hsae.auto.USER_BEHAVIOR_SERVICE"
    private const val DESCRIPTOR = "com.hsae.autosdk.userbehavior.IUserBehaviorManager"
    private const val OEM_PACKAGE = "com.hsae.d531mc.userbehaviors"
    private const val OEM_CLASS = "$OEM_PACKAGE.GatherUserBehaviorsService"
    private const val PHONE_OPERATION = 6
    private const val NAVI_ON_TOP = 5
    private const val OEM_BACK = 0
    private const val OEM_HOME = 2
    private const val TEL_OFF = 26
    private const val TEL_ON = 27
    private const val SEEK_UP = 31
    private const val SEEK_DOWN = 32
    private const val PANEL_VOLUME_UP = 6
    private const val PANEL_VOLUME_DOWN = 7
    private const val WHEEL_VOLUME_UP = 28
    private const val WHEEL_VOLUME_DOWN = 29
    private const val BIND_WAIT_MS = 5_000L

    @Volatile private var relay: IBinder? = null
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var bound = false
    @Volatile private var status = "disabled"
    private val received = AtomicInteger()
    private val forwarded = AtomicInteger()
    private val failures = AtomicInteger()
    private val hangupGate = NissanCarPlayCallKeyGate()
    private val answerGate = NissanCarPlayCallKeyGate()
    private val siriGate = NissanPhoneKeySiriGate()
    private val seekVolumeGate = NissanSeekVolumeGate()
    @Volatile private var log: SessionLogFile? = null
    private var connection: ServiceConnection? = null

    fun enabled(context: Context): Boolean = BuildConfig.NISSAN_ANDROID_44_PROFILE &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, value: Boolean): Boolean {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return false
        return runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, NissanPhoneKeyProbeService::class.java),
                if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
            record(context, "setting enabled=$value; reboot needed for OEM service selection")
            true
        }.getOrElse {
            record(context, "setting failed error=${it.javaClass.simpleName}")
            false
        }
    }

    fun summary(context: Context): String {
        val preferred = runCatching {
            context.packageManager.resolveService(Intent(ACTION), 0)?.serviceInfo?.let {
                "${it.packageName}/${it.name}"
            } ?: "none"
        }.getOrElse { "error=${it.javaClass.simpleName}" }
        val matches = runCatching {
            context.packageManager.queryIntentServices(Intent(ACTION), 0)
                .map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}(priority=${it.priority})" }
                .take(8).joinToString(",")
        }.getOrElse { "error=${it.javaClass.simpleName}" }
        return "Nissan phone key probe: enabled=${enabled(context)} localBound=$bound status=$status " +
            "received=${received.get()} forwarded=${forwarded.get()} failures=${failures.get()} " +
            "${NissanOemKeyRelay.summary()} " +
            "oemService=${NissanOemKeyRelay.serviceStatus(context, NissanPhoneKeyProbeService::class.java)} " +
            "preferred=$preferred candidates=[$matches]"
    }

    @Synchronized fun onBind(context: Context) {
        if (bound) return
        bound = true
        status = "binding_original"
        log = SessionLogFile(File(File(context.filesDir, "logs"), "diplay.log"))
        ready = CountDownLatch(1)
        val original = ComponentName(OEM_PACKAGE, OEM_CLASS)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                relay = service
                status = "forwarding_to=$name"
                ready.countDown()
                record(context, "original connected component=$name descriptor=${runCatching { service.interfaceDescriptor }.getOrNull()}")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                relay = null
                ready = CountDownLatch(1)
                status = "original_disconnected"
                record(context, "original disconnected component=$name")
            }
        }
        connection = serviceConnection
        val started = runCatching {
            context.bindService(Intent(ACTION).setComponent(original), serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            status = "bind_error=${it.javaClass.simpleName}"
            false
        }
        if (!started) status = "original_bind_failed"
        record(context, "probe bound pid=${android.os.Process.myPid()} original=$original bindStarted=$started")
    }

    @Synchronized fun onDestroy(context: Context) {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        relay = null
        ready.countDown()
        bound = false
        status = "unbound"
        record(context, "probe unbound")
    }

    private fun original(): IBinder? {
        relay?.takeIf(IBinder::isBinderAlive)?.let { return it }
        val latch = ready
        try { latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return relay?.takeIf(IBinder::isBinderAlive)
    }

    fun binder(context: Context): IBinder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            var phoneOperation: Int? = null
            if (code == PHONE_OPERATION) {
                val start = data.dataPosition()
                try {
                    data.enforceInterface(DESCRIPTOR)
                    val operation = data.readInt()
                    if (operation == OEM_HOME ||
                        operation == TEL_ON || operation == TEL_OFF || operation == SEEK_UP || operation == SEEK_DOWN ||
                        operation == PANEL_VOLUME_UP || operation == PANEL_VOLUME_DOWN ||
                        operation == WHEEL_VOLUME_UP || operation == WHEEL_VOLUME_DOWN) {
                        phoneOperation = operation
                        received.incrementAndGet()
                        val event = when (operation) {
                            OEM_HOME -> "home"
                            TEL_ON -> "telOn"
                            TEL_OFF -> "telOff"
                            SEEK_UP -> "seekUp"
                            SEEK_DOWN -> "seekDown"
                            PANEL_VOLUME_UP -> "panelVolumeUp"
                            PANEL_VOLUME_DOWN -> "panelVolumeDown"
                            WHEEL_VOLUME_UP -> "wheelVolumeUp"
                            else -> "wheelVolumeDown"
                        }
                        record(context, "event=$event " +
                            "operation=$operation callerUid=${getCallingUid()} " +
                            "elapsedMs=${SystemClock.elapsedRealtime()} relayProcess=${android.os.Process.myPid()}")
                    }
                } catch (error: Exception) {
                    failures.incrementAndGet()
                    record(context, "event parse failed error=${error.javaClass.simpleName}")
                } finally {
                    data.setDataPosition(start)
                }
            }
            val target = original()
            if (target == null) {
                failures.incrementAndGet()
                status = "original_unavailable"
                record(context, "forward failed code=$code original unavailable")
                return fallback(code, reply, flags)
            }
            return try {
                val sent = target.transact(code, data, reply, flags)
                if (sent) forwarded.incrementAndGet() else failures.incrementAndGet()
                if (!sent) record(context, "forward failed code=$code transact=false")
                if (sent && phoneOperation != null && shouldDispatchToDiPlay(phoneOperation)) {
                    // The factory receiver must see the unmodified event first.
                    runCatching { NissanOemKeyRelay.userBehavior(context, phoneOperation) }.onFailure {
                        record(context, "CarPlay key handling failed error=${it.javaClass.simpleName}")
                    }
                }
                if (sent) true else fallback(code, reply, flags)
            } catch (error: Exception) {
                failures.incrementAndGet()
                record(context, "forward failed code=$code error=${error.javaClass.simpleName}")
                fallback(code, reply, flags)
            }
        }
    }

    internal fun onPhoneOperation(context: Context, operation: Int) {
        if (!enabled(context)) return
        if (!shouldDispatchToDiPlay(operation)) return
        val current = CarPlayBackgroundSession.snapshot()
        val call = current?.controller?.currentCallSnapshot()
        if (operation == PANEL_VOLUME_UP || operation == PANEL_VOLUME_DOWN ||
            operation == WHEEL_VOLUME_UP || operation == WHEEL_VOLUME_DOWN) {
            val channel = current?.sink?.nissanActiveAudioChannel() ?: "none"
            record(context, "physical volume operation=$operation channel=$channel " +
                "CarPlayActive=${CarPlayBackgroundSession.active} ${NissanOemVolumeDiagnostics.summary()} " +
                "action=observe_oem_volume_routing")
            return
        }
        if (operation == SEEK_UP || operation == SEEK_DOWN) {
            val reason = when {
                !CarPlayBackgroundSession.active || current == null -> "no_carplay_session"
                !current.controller.isWirelessSession() -> "not_wireless"
                !AirPlayPersistence.loadCarPlayAudioOutputEnabled(context) -> "carplay_audio_disabled"
                current.controller.activeAirPlaySessionToken() == null -> "airplay_inactive"
                else -> null
            }
            if (reason != null) {
                record(context, "CarPlay seek operation=$operation eligible=false sent=false reason=$reason")
                return
            }
            val session = current ?: return
            val channel = session.sink.nissanActiveAudioChannel()
            val target = NissanSeekAudioRouting.target(call?.phase, channel)
            if (target == NissanSeekAudioRouting.Target.BLOCKED) {
                record(context, "CarPlay seek operation=$operation action=blocked reason=call_ringing")
                return
            }
            if (target == NissanSeekAudioRouting.Target.CALL ||
                target == NissanSeekAudioRouting.Target.NAVIGATION) {
                if (!seekVolumeGate.claim(operation, target, SystemClock.elapsedRealtime())) {
                    record(context, "CarPlay seek operation=$operation action=volume target=$target ignored=repeat")
                    return
                }
                val before = if (target == NissanSeekAudioRouting.Target.CALL)
                    AirPlayPersistence.loadCallVolumePercent(context)
                else AirPlayPersistence.loadNavigationVolumePercent(context)
                val after = NissanSeekAudioRouting.adjustedPercent(before, operation == SEEK_UP)
                if (after == before) {
                    NissanCarPlayVolumeOverlay.show(context, target, after)
                    record(context, "CarPlay seek operation=$operation action=volume target=$target " +
                        "channel=$channel before=$before after=$after applied=false reason=limit")
                    return
                }
                val applied = runCatching {
                    if (target == NissanSeekAudioRouting.Target.CALL)
                        AirPlayPersistence.saveCallVolumePercent(context, after)
                    else AirPlayPersistence.saveNavigationVolumePercent(context, after)
                    session.sink.updateRelativeVolumes(
                        AirPlayPersistence.loadMediaVolumePercent(context),
                        AirPlayPersistence.loadNavigationVolumePercent(context),
                        AirPlayPersistence.loadCallVolumePercent(context),
                    )
                    true
                }.getOrElse { false }
                if (applied) NissanCarPlayVolumeOverlay.show(context, target, after)
                record(context, "CarPlay seek operation=$operation action=volume target=$target " +
                    "channel=$channel before=$before after=$after applied=$applied")
                return
            }
            val sent = CarPlayMediaKeys.sendFromOem(
                if (operation == SEEK_UP) CarPlayMediaButton.PREVIOUS else CarPlayMediaButton.NEXT,
                "user-behavior",
            )
            record(context, "CarPlay seek operation=$operation action=media sent=$sent channel=$channel")
            return
        }
        if (operation == TEL_ON) {
            if (call == null) {
                val siriReason = when {
                    !CarPlayBackgroundSession.active || current == null -> "no_carplay_session"
                    !current.controller.isWirelessSession() -> "not_wireless"
                    !AirPlayPersistence.loadCarPlayAudioOutputEnabled(context) -> "carplay_audio_disabled"
                    current.controller.activeAirPlaySessionToken() == null -> "airplay_inactive"
                    else -> null
                }
                if (siriReason != null) {
                    record(context, "CarPlay Siri eligible=false reason=$siriReason")
                    return
                }
                val controller = current!!.controller
                val factoryState = NissanOemPhoneDiagnostics.currentPhoneState()
                if (factoryState != 0) {
                    record(context, "CarPlay Siri eligible=false reason=oem_phone_state:$factoryState")
                    return
                }
                val pressMs = SystemClock.elapsedRealtime()
                if (!siriGate.claim(controller, pressMs)) {
                    record(context, "CarPlay Siri eligible=true queued=false reason=duplicate")
                    return
                }
                NissanOemVoiceUi.requestSiriAfterPhoneKey(context, controller) {
                    siriGate.release(controller, pressMs)
                }
                record(context, "CarPlay Siri eligible=true queued=true reason=phone_key")
                return
            }
            val reason = when {
                !CarPlayBackgroundSession.active -> "no_carplay_session"
                !current.controller.isWirelessSession() -> "not_wireless"
                !AirPlayPersistence.loadCarPlayAudioOutputEnabled(context) -> "carplay_audio_disabled"
                current.controller.activeAirPlaySessionToken() == null -> "airplay_inactive"
                call.phase != CarPlayCallPhase.RINGING -> "call_not_ringing:${call.phase}"
                else -> null
            }
            if (reason != null) {
                record(context, "CarPlay answer eligible=false reason=$reason")
                return
            }
            val controller = current.controller
            val epoch = call.epoch
            val factoryState = NissanOemPhoneDiagnostics.currentPhoneState()
            if (factoryState != 0) {
                record(context, "CarPlay answer eligible=false reason=oem_phone_state:$factoryState")
                return
            }
            if (!answerGate.claim(controller, epoch)) {
                record(context, "CarPlay answer eligible=true sent=false reason=duplicate")
                return
            }
            val sent = controller.answerCall()
            if (!sent) answerGate.release(controller, epoch)
            record(context, "CarPlay answer eligible=true sent=$sent reason=${if (sent) "telephony_hook" else "session_unavailable"}")
            if (sent) NissanOemVoiceUi.hideAfterCarPlayAnswer(context, controller, epoch)
            return
        }
        if (operation != TEL_OFF) return
        val reason = when {
            !CarPlayBackgroundSession.active || current == null -> "no_carplay_session"
            !current.controller.isWirelessSession() -> "not_wireless"
            !AirPlayPersistence.loadCarPlayAudioOutputEnabled(context) -> "carplay_audio_disabled"
            current.controller.activeAirPlaySessionToken() == null -> "airplay_inactive"
            call?.phase != CarPlayCallPhase.ACTIVE && call?.phase != CarPlayCallPhase.RINGING ->
                "call_not_active:${call?.phase ?: "NONE"}"
            else -> null
        }
        if (reason != null) {
            record(context, "CarPlay hangup eligible=false reason=$reason")
            return
        }
        val controller = current!!.controller
        val epoch = call!!.epoch
        val factoryState = NissanOemPhoneDiagnostics.currentPhoneState()
        if (factoryState != 0) {
            record(context, "CarPlay hangup eligible=false reason=oem_phone_state:$factoryState")
            return
        }
        if (!hangupGate.claim(controller, epoch)) {
            record(context, "CarPlay hangup eligible=true sent=false reason=duplicate")
            return
        }
        val sent = controller.endCall()
        if (!sent) hangupGate.release(controller, epoch)
        record(context, "CarPlay hangup eligible=true sent=$sent reason=${if (sent) "telephony_drop" else "session_unavailable"}")
    }

    /** Back and Home remain exclusively with the factory; CarPlay keys still use the relay. */
    internal fun shouldDispatchToDiPlay(operation: Int): Boolean =
        operation != OEM_BACK && operation != OEM_HOME

    private fun fallback(code: Int, reply: Parcel?, flags: Int): Boolean {
        if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return true
        reply.setDataPosition(0)
        if (code == NAVI_ON_TOP) {
            reply.writeNoException()
            reply.writeInt(0)
        } else {
            reply.writeException(IllegalStateException("Nissan user-behavior service unavailable"))
        }
        return true
    }

    private fun record(context: Context, message: String) {
        val target = log ?: SessionLogFile(File(File(context.filesDir, "logs"), "diplay.log"))
        AsyncDiagnosticLog.append(target, "OEM phone key probe: $message")
    }
}

class NissanPhoneKeyProbeService : Service() {
    private val probeBinder by lazy { NissanPhoneKeyProbe.binder(this) }

    override fun onBind(intent: Intent?): IBinder {
        NissanPhoneKeyProbe.onBind(this)
        return probeBinder
    }

    override fun onDestroy() {
        NissanPhoneKeyProbe.onDestroy(this)
        super.onDestroy()
    }
}
