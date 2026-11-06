package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.appcompat.content.res.AppCompatResources
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 does not supply an API 19 runtime; the app's minSdk and lint cover API use.
@Config(sdk = [23])
class SettingsVisualsResourceTest {
    @Test fun settingsIconsLoadAndDraw() {
        val context = RuntimeEnvironment.getApplication()
        val icons = intArrayOf(
            R.drawable.ic_dp_overview, R.drawable.ic_dp_connection,
            R.drawable.ic_dp_display, R.drawable.ic_dp_audio,
            R.drawable.ic_dp_navigation, R.drawable.ic_dp_vehicle,
            R.drawable.ic_dp_diagnostics, R.drawable.ic_dp_advanced,
            R.drawable.ic_dp_automation, R.drawable.ic_dp_controls,
            R.drawable.ic_dp_car, R.drawable.ic_dp_permissions,
            R.drawable.ic_dp_about, R.drawable.ic_dp_language,
            R.drawable.ic_dp_call,
        )
        for (id in icons) {
            val drawable = AppCompatResources.getDrawable(context, id)
            assertNotNull("Drawable $id", drawable)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            drawable!!.setBounds(0, 0, 48, 48)
            drawable.draw(Canvas(bitmap))
            assertTrue("Drawable $id was empty", (0 until 48).any { x ->
                (0 until 48).any { y -> bitmap.getPixel(x, y) ushr 24 != 0 }
            })
        }
    }

    @Test fun flatVolumeProgressKeepsTrackLevelAndTouchInput() {
        val context = RuntimeEnvironment.getApplication()
        val slider = CarPlayProgressStyle.seekBar(context)
        slider.progress = 50
        slider.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(88, android.view.View.MeasureSpec.EXACTLY))
        slider.layout(0, 0, 480, 88)
        val track = CarPlayProgressStyle.track(context)
        track.setBounds(0, 0, 480, 88)
        track.level = 5_000
        assertNull(slider.thumb)
        assertEquals(5_000, track.level)
        assertEquals(480, track.bounds.width())

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 350f, 44f, 0)
        val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 350f, 44f, 0)
        slider.onTouchEvent(down)
        slider.onTouchEvent(up)
        down.recycle()
        up.recycle()
        assertTrue("Flat slider remains touch adjustable", slider.progress > 50)
    }
}
