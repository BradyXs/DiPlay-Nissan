package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.view.InputDevice
import android.view.KeyEvent

/** Native calls and CarPlay calls can both take the head unit out of normal audio mode. */
internal fun inCall(context: Context): Boolean =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let {
        it != null && it != AudioManager.MODE_NORMAL
    }

/** Keep a window's key stream well formed if the session or setting changes mid-press. */
internal class WheelKeyPresses {
    enum class Action { PASS, CONSUME }

    private val consumed = mutableMapOf<Any, Boolean>()

    fun hasConsumedPress(key: Any): Boolean = consumed[key] == true

    fun filter(key: Any?, down: Boolean, firstPress: Boolean, decide: () -> Action): Action {
        key ?: return Action.PASS
        if (!down) return disposition(consumed.remove(key) ?: false)
        consumed[key]?.let { return disposition(it) }
        if (!firstPress) return Action.PASS
        return decide().also { consumed[key] = it == Action.CONSUME }
    }

    private fun disposition(consume: Boolean) = if (consume) Action.CONSUME else Action.PASS
}

/** A key that reaches an Android activity window, identified across reconnects by device name. */
data class WheelKey(val code: Int, val scan: Int, val device: String) {
    override fun toString(): String = "$code/$scan"

    fun encode(): String = "$code|$scan|$device"

    companion object {
        fun of(event: KeyEvent): WheelKey = WheelKey(event.keyCode, event.scanCode,
            runCatching { InputDevice.getDevice(event.deviceId)?.name }.getOrNull() ?: "?")

        fun decode(text: String?): WheelKey? = text?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let {
            WheelKey(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
        }
    }
}

/** Suppresses duplicate Siri requests from a held Android window key. */
class WheelSiriKey {
    private var lastPressMillis: Long? = null

    fun opens(eventTimeMillis: Long): Boolean {
        val last = lastPressMillis
        lastPressMillis = eventTimeMillis
        return last == null || eventTimeMillis - last >= REPEAT_GAP_MILLIS
    }

    companion object { const val REPEAT_GAP_MILLIS = 400L }
}

/** Existing window key assignment survives removal of the inaccessible service. */
object WheelKeySettings {
    private const val PREFS = "diplay_wheel_map_zoom"
    private const val KEY_SIRI = "siri_key"

    fun siriKey(context: Context): Boolean = prefs(context).getBoolean(KEY_SIRI, false)

    fun setSiriKey(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIRI, enabled).apply()
    }

    fun isSiriKey(context: Context, pressed: WheelKey): Boolean = siriKey(context) && key(context) == pressed

    fun key(context: Context): WheelKey? = WheelKey.decode(prefs(context).getString("key_SIRI", null))

    fun assignSiriKey(context: Context, key: WheelKey) {
        prefs(context).edit().putString("key_SIRI", key.encode()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
