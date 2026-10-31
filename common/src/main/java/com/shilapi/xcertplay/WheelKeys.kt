package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager

/** Native calls and CarPlay calls can both take the head unit out of normal audio mode. */
internal fun inCall(context: Context): Boolean =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let {
        it != null && it != AudioManager.MODE_NORMAL
    }

/** Keep a window's key stream well formed if the session or setting changes mid-press. */
internal class WheelKeyPresses {
    enum class Action { PASS, CONSUME }

    private val consumed = mutableMapOf<Any, Boolean>()

    fun filter(key: Any?, down: Boolean, firstPress: Boolean, decide: () -> Action): Action {
        key ?: return Action.PASS
        if (!down) return disposition(consumed.remove(key) ?: false)
        consumed[key]?.let { return disposition(it) }
        if (!firstPress) return Action.PASS
        return decide().also { consumed[key] = it == Action.CONSUME }
    }

    private fun disposition(consume: Boolean) = if (consume) Action.CONSUME else Action.PASS
}

/** Siri preference shared by the Nissan phone-key relay and settings. */
object WheelKeySettings {
    private const val PREFS = "diplay_wheel_map_zoom"
    private const val KEY_SIRI = "siri_key"

    fun siriKey(context: Context): Boolean = prefs(context).getBoolean(KEY_SIRI, false)

    fun setSiriKey(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIRI, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
