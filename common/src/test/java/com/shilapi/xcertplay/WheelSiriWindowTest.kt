package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelSiriWindowTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var settings: DiPlayActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        WheelKeySettings.setSiriKey(host, true)
        WheelKeySettings.assignSiriKey(host, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        for ((activity, name) in listOf(host to "mainHandler", settings to "handler")) {
            (activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) as Handler).removeCallbacksAndMessages(null)
        }
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun heldAssignedKeyKeepsItsReleaseAfterCallSessionAndSettingChanges() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true)))
        WheelKeySettings.setSiriKey(host, false)
        field("activeAirPlaySession", null)
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun anAssignedStandardVoiceKeyDoesNotAlsoInvokeTheLegacyReleasePath() {
        val code = KeyEvent.KEYCODE_VOICE_ASSIST
        WheelKeySettings.assignSiriKey(host, WheelKey(code, 0, "?"))
        assertTrue(host.dispatchKeyEvent(key(code, true)))
        assertTrue(host.dispatchKeyEvent(key(code, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun anUnassignedVoiceKeyKeepsItsExistingReleaseBehavior() {
        WheelKeySettings.setSiriKey(host, false)
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_VOICE_ASSIST, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_VOICE_ASSIST, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun windowLearningConsumesRepeatsAndReleaseAfterAssignmentFinishes() {
        var learned = 0
        learn { learned++ }
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true)))
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true, repeat = 1)))
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, false)))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F7, WheelKeySettings.key(settings)?.code)
    }

    @Test fun aCallCancelsWindowLearningWithoutSavingTheCallKey() {
        var learned = 0
        learn { learned++ }
        settings.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true))
        settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, false))
        assertEquals(0, learned)
        assertEquals(KeyEvent.KEYCODE_F6, WheelKeySettings.key(settings)?.code)
    }

    private fun learn(done: (WheelKey) -> Unit) {
        settings.javaClass.getDeclaredMethod("learnInWindow",
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }
            .invoke(settings, {}, done)
    }

    private fun field(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)

    private fun key(code: Int, down: Boolean, repeat: Int = 0) =
        KeyEvent(1_000, 1_000, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, -1, 0)
}
