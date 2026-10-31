package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controllers = mockConstruction(CarPlayController::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test fun configuredFingerCountsOpenTheFullSettingsWithoutRestarting() {
        val controller = attachController()
        for (fingers in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, fingers)
            invoke("loadPersistedSettings")
            gesture(fingers)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
            assertEquals("settings", intent.getStringExtra("page"))
            assertTrue(intent.getBooleanExtra(SETTINGS_FROM_CARPLAY_EXTRA, false))
            assertSame(controller, field("controller"))
            assertEquals(0, field("restartGeneration"))
        }
    }

    @Test fun wrongFingerCountsAndNonDownwardSwipesDoNotOpenSettings() {
        for (configured in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, configured)
            invoke("loadPersistedSettings")
            for (actual in 2..4) {
                if (actual == configured) continue
                gesture(actual)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            gesture(configured, x = 1000f, y = 130f)
            assertNull(shadowOf(activity).nextStartedActivity)
            gesture(configured, y = -500f)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun liftingAFingerCancelsTrackingUntilTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_UP, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_MOVE, 3, 700f)
        assertNull(shadowOf(activity).nextStartedActivity)
        touch(MotionEvent.ACTION_UP, 1, 700f)
        gesture(3)
        assertEquals("settings", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun resumingReloadsSavedSettingsWithoutReplacingTheController() {
        val controller = attachController()
        AirPlayPersistence.saveSettingsGestureFingers(activity, 4)
        AirPlayPersistence.saveDebugLogsEnabled(activity, true)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("onResume")
        assertEquals(4, field("gestureFingerCount"))
        assertEquals(true, field("debugLogsEnabled"))
        assertEquals(MfiTarget.LOCAL, field("mfiTarget"))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun safeAreaCancelDiscardsDraftAndReturnsToDisplaySettings() {
        fakeRunningSession()
        val original = SafeAreaRect(20, 20, 1800, 900)
        AirPlayPersistence.saveSafeAreaRect(activity, 1920, 990, original)
        invoke("openSafeAreaEditor")
        (field("safeAreaEditorView") as SafeAreaEditorView).setRect(SafeAreaRect(30, 30, 1700, 800), 1920, 990)
        invoke("closeSafeAreaEditor")
        assertEquals(original, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 990))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("settings", intent.getStringExtra("page"))
        assertEquals(SettingsCategory.DISPLAY.name, intent.getStringExtra(SETTINGS_CATEGORY_EXTRA))
        assertTrue(intent.getBooleanExtra(SETTINGS_FROM_CARPLAY_EXTRA, false))
    }

    @Test fun safeAreaSaveUsesCurrentDisplaySize() {
        val controller = attachController()
        val sink = mock(AndroidMediaSink::class.java)
        CarPlayBackgroundSession.store(controller, sink, 1920, 990, activity,
            CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, false, false, 1920, 990)) { }
        PendingReconnect.clear()
        invoke("openSafeAreaEditor")
        val edited = SafeAreaRect(30, 30, 1700, 800)
        (field("safeAreaEditorView") as SafeAreaEditorView).setRect(edited, 1920, 990)
        invoke("saveSafeAreaEditor")
        assertEquals(edited, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 990))
        assertTrue(PendingReconnect.isPending(controller))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals("settings", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        PendingReconnect.clear()
    }

    @Test fun safeAreaResetIsOnlyPersistedWhenSaved() {
        fakeRunningSession()
        val original = SafeAreaRect(20, 20, 1800, 900)
        AirPlayPersistence.saveSafeAreaRect(activity, 1920, 990, original)
        invoke("openSafeAreaEditor")
        fun buttons(view: View): Sequence<Button> = sequence {
            if (view is Button) yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(buttons(view.getChildAt(i)))
        }
        buttons(field("safeAreaEditor") as View).single { it.text == activity.getString(R.string.reset) }.performClick()
        assertEquals(original, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 990))
        invoke("saveSafeAreaEditor")
        assertNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 990))
    }

    private fun fakeRunningSession() {
        val stop: (() -> Unit) -> Unit = { it() }
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, stop)
    }

    @Test fun localRuntimeDoesNotRequestCh341Devices() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        assertTrue(config.ch341Devices.isEmpty())
    }

    @Test fun ch341AttachmentKeepsTheWirelessSession() {
        val controller = attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x1a86)
        `when`(device.productId).thenReturn(0x5512)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        assertTrue(field("wirelessEnabled") as Boolean)
        assertSame(controller, field("controller"))
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun iphoneAttachmentSwitchesToWiredTransportWithoutFinishing() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertFalse(field("wirelessEnabled") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun switchingToUsbRequestsVpnConsentAndDoesNotRepeatPendingConsent() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        setField("vpnReady", true) // Previously granted permission may have been revoked.
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        val method = activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
        method.invoke(activity, intent)
        assertTrue(field("awaitingVpnConsent") as Boolean)
        assertFalse(field("vpnReady") as Boolean)
        assertNull(field("controller"))
        assertEquals("test.VPN_CONSENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
        method.invoke(activity, intent)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(activity.isFinishing)
    }

    @Test fun onlyIphoneAttachmentSelectsWiredTransport() {
        val method = activity.javaClass.getDeclaredMethod("isIphoneUsbAttachment", Intent::class.java)
            .apply { isAccessible = true }
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val attached = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        assertEquals(true, method.invoke(activity, attached))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device)))
    }

    private fun gesture(fingers: Int, x: Float = 400f, y: Float = 700f) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, y, x)
        touch(MotionEvent.ACTION_UP, 1, y, x)
    }

    private fun touch(action: Int, count: Int, y: Float, x: Float = 400f) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 50f; this.y = y; pressure = 1f; size = 1f } }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexedAction, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController(): CarPlayController = CarPlayController(
        activity, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { setField("controller", it) }

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
