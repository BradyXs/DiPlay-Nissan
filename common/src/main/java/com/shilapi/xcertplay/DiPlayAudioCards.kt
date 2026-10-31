package com.shilapi.xcertplay

import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer

/** Builds audio controls; the activity owns persistence and the active CarPlay session. */
internal class DiPlayAudioCards(
    private val activity: DiPlayActivity,
    private val ui: DiPlayCardUi,
    private val handler: Handler,
    private val log: (String) -> Unit,
    private val controls: Controls,
) {
    interface Controls {
        fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int,
            reconnects: Boolean = true, save: (Int) -> Unit)
        fun toggle(parent: LinearLayout, title: String, description: String,
            checked: Boolean, save: (Boolean) -> Unit)
        fun markReconnectNeeded()
        fun applyRelativeVolumes(media: Int? = null, navigation: Int? = null, call: Int? = null)
    }

    fun routingCard(card: LinearLayout, nissanProfile: Boolean) = with(ui) {
        if (nissanProfile) {
            controls.choice(card, activity.getString(R.string.settings_nissan_audio_mode),
                listOf(activity.getString(R.string.settings_nissan_audio_bluetooth),
                    activity.getString(R.string.settings_nissan_audio_carplay)),
                if (AirPlayPersistence.loadCarPlayAudioOutputEnabled(activity)) 1 else 0,
                reconnects = false) { selected ->
                AirPlayPersistence.saveCarPlayAudioOutputEnabled(activity, selected == 1)
                controls.markReconnectNeeded()
            }
            card.addView(label(activity.getString(R.string.settings_nissan_audio_mode_description),
                14, DiPlayCardUi.MUTED))
            relativeVolume(card, R.string.settings_carplay_media_volume,
                AirPlayPersistence.loadMediaVolumePercent(activity),
                apply = { controls.applyRelativeVolumes(media = it) },
                save = { AirPlayPersistence.saveMediaVolumePercent(activity, it) })
            relativeVolume(card, R.string.settings_carplay_navigation_volume,
                AirPlayPersistence.loadNavigationVolumePercent(activity),
                apply = { controls.applyRelativeVolumes(navigation = it) },
                save = { AirPlayPersistence.saveNavigationVolumePercent(activity, it) })
            oemNavigationVolume(card)
            relativeVolume(card, R.string.settings_carplay_call_volume,
                AirPlayPersistence.loadCallVolumePercent(activity),
                apply = { controls.applyRelativeVolumes(call = it) },
                save = { AirPlayPersistence.saveCallVolumePercent(activity, it) })
            oemPhoneVolume(card)
            card.addView(label(activity.getString(R.string.settings_carplay_volume_description),
                14, DiPlayCardUi.MUTED))
        } else {
            controls.toggle(card, activity.getString(R.string.settings_carplay_audio_output),
                activity.getString(R.string.settings_carplay_audio_output_description),
                AirPlayPersistence.loadCarPlayAudioOutputEnabled(activity)) {
                AirPlayPersistence.saveCarPlayAudioOutputEnabled(activity, it)
                controls.markReconnectNeeded()
            }
        }
        val presets = MediaAudioBuffer.presets
        controls.choice(card, activity.getString(R.string.music_buffer),
            listOf(activity.getString(R.string.s_300_ms_default), activity.getString(R.string.s_500_ms),
                activity.getString(R.string.s_1000_ms_most_stable)),
            presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(activity)).coerceAtLeast(0)) {
            AirPlayPersistence.saveMediaBufferMillis(activity, presets[it])
        }
    }

    fun relativeVolume(parent: LinearLayout, titleId: Int, initial: Int,
        apply: (Int) -> Unit, save: (Int) -> Unit) = with(ui) {
        val title = activity.getString(titleId)
        var dragging = false
        val commit: (Int) -> Unit = { percent ->
            save(percent)
            log("Audio volume: $title percent=$percent active=${CarPlayBackgroundSession.active}")
        }
        index(title)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 18, DiPlayCardUi.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("${initial.coerceIn(0, 100)}%", 18, DiPlayCardUi.ACCENT)
        header.addView(value)
        parent.addView(header)
        val slider = SeekBar(activity).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            contentDescription = title
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "$progress%"
                    if (fromUser) {
                        apply(progress)
                        if (!dragging) commit(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    dragging = true
                    seekBar.requestFocus()
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging = false
                    commit(seekBar.progress)
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
                    return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    progress = (progress + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5).coerceIn(0, 100)
                    apply(progress)
                    commit(progress)
                }
                true
            }
        }
        parent.addView(slider, LinearLayout.LayoutParams(-1, -2))
        parent.addView(space(12))
    }

    fun oemNavigationVolume(parent: LinearLayout) = oemVolume(parent,
        R.string.settings_nissan_navigation_hardware_volume,
        R.string.settings_nissan_navigation_hardware_volume_description,
        NissanOemNavigationVolume.MAX_LEVEL, "navigation",
        NissanOemNavigationVolume::readAsync, NissanOemNavigationVolume::setAsync)

    fun oemPhoneVolume(parent: LinearLayout) = oemVolume(parent,
        R.string.settings_nissan_phone_hardware_volume,
        R.string.settings_nissan_phone_hardware_volume_description,
        NissanOemPhoneVolume.MAX_LEVEL, "phone",
        NissanOemPhoneVolume::readAsync, NissanOemPhoneVolume::setAsync)

    private fun oemVolume(parent: LinearLayout, titleId: Int, descriptionId: Int,
        maxLevel: Int, logType: String,
        readAsync: (((Int?, String?) -> Unit) -> Unit),
        setAsync: ((Int, (Int?, String?) -> Unit) -> Unit)) = with(ui) {
        val title = activity.getString(titleId)
        index(title)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 18, DiPlayCardUi.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(activity.getString(R.string.settings_nissan_navigation_hardware_volume_loading),
            18, DiPlayCardUi.ACCENT)
        header.addView(value)
        parent.addView(header)
        var revision = 0
        val slider = SeekBar(activity).apply {
            max = maxLevel
            isEnabled = false
            isFocusableInTouchMode = true
            contentDescription = title
        }
        fun commit(level: Int) {
            val requested = level.coerceIn(0, maxLevel)
            revision++
            val thisRevision = revision
            value.text = "$requested/$maxLevel"
            setAsync(requested) { actual, error ->
                log("OEM $logType volume: requested=$requested actual=${actual ?: "unknown"} error=${error ?: "none"}")
                handler.post {
                    if (activity.isFinishing || activity.isDestroyed || thisRevision != revision || slider.parent == null)
                        return@post
                    if (actual == null) {
                        value.text = activity.getString(R.string.settings_nissan_navigation_hardware_volume_unavailable)
                    } else {
                        slider.progress = actual.coerceIn(0, maxLevel)
                        value.text = "${slider.progress}/$maxLevel"
                    }
                }
            }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) value.text = "$progress/$maxLevel"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { seekBar.requestFocus() }
            override fun onStopTrackingTouch(seekBar: SeekBar) { commit(seekBar.progress) }
        })
        slider.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
                return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                slider.progress = (slider.progress + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                    .coerceIn(0, maxLevel)
                commit(slider.progress)
            }
            true
        }
        parent.addView(slider, LinearLayout.LayoutParams(-1, -2))
        parent.addView(label(activity.getString(descriptionId), 14, DiPlayCardUi.MUTED))
        parent.addView(space(12))
        readAsync { current, error ->
            log("OEM $logType volume: initial=${current ?: "unknown"} error=${error ?: "none"}")
            handler.post {
                if (activity.isFinishing || activity.isDestroyed || revision != 0 || slider.parent == null)
                    return@post
                if (current == null) {
                    value.text = activity.getString(R.string.settings_nissan_navigation_hardware_volume_unavailable)
                } else {
                    slider.progress = current.coerceIn(0, maxLevel)
                    value.text = "${slider.progress}/$maxLevel"
                    slider.isEnabled = true
                }
            }
        }
    }
}
