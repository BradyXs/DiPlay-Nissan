package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NissanAndroid44ProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test fun ordinaryBuildDoesNotApplyNissanDefaults() {
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveCarPlayAudioOutputEnabled(context, true)
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveDirectSurfaceView(context, false)

        assertFalse(AirPlayPersistence.applyNissanAndroid44DefaultsOnce(context, profileEnabled = false))

        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadCarPlayAudioOutputEnabled(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertFalse(AirPlayPersistence.loadDirectSurfaceView(context))
        assertFalse(prefs.contains("nissan_android_44_profile_version"))
    }

    @Test fun nissanDefaultsApplyOnceAndKeepLaterUserChoices() {
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveCarPlayAudioOutputEnabled(context, true)
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveDirectSurfaceView(context, false)
        AirPlayPersistence.saveWidthPhysicalMm(context, 300)

        assertTrue(AirPlayPersistence.applyNissanAndroid44DefaultsOnce(context, profileEnabled = true))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadCarPlayAudioOutputEnabled(context))
        assertFalse(AirPlayPersistence.loadHevcEnabled(context))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context))
        assertTrue(AirPlayPersistence.loadDirectSurfaceView(context))
        assertEquals(300, AirPlayPersistence.loadWidthPhysicalMm(context))

        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveCarPlayAudioOutputEnabled(context, true)
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveDirectSurfaceView(context, false)

        assertFalse(AirPlayPersistence.applyNissanAndroid44DefaultsOnce(context, profileEnabled = true))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadCarPlayAudioOutputEnabled(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertFalse(AirPlayPersistence.loadDirectSurfaceView(context))
    }
}
