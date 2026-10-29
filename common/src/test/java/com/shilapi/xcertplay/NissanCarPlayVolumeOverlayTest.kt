package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NissanCarPlayVolumeOverlayTest {
    @Test fun guidanceVolumeIsAnchoredToTopOfVisibleCarPlayWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        NissanCarPlayVolumeOverlay.attachHost(container)
        NissanCarPlayVolumeOverlay.show(activity, NissanSeekAudioRouting.Target.NAVIGATION, 60)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, container.childCount)
        val params = container.getChildAt(0).layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, params.gravity)
        assertEquals((6 * activity.resources.displayMetrics.density + 0.5f).toInt(), params.topMargin)

        NissanCarPlayVolumeOverlay.detachHost(container)
        shadowOf(Looper.getMainLooper()).idle()
        activity.finish()
    }
}
