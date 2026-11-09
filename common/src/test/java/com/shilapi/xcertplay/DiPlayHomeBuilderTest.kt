package com.shilapi.xcertplay

import com.shilapi.xcertplay.ui.components.DiPlayCardUi
import com.shilapi.xcertplay.ui.pages.DiPlayHomeBuilder

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w800dp-h480dp-land", manifest = Config.NONE)
class DiPlayHomeBuilderTest {
    @Test fun nissanHomeShowsOneLogoAndBothConnectionActions() {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var wireless = 0
        var wired = 0
        val widgets = DiPlayHomeBuilder(activity, DiPlayCardUi(activity), DiPlayHomeBuilder.Actions(
            wireless = { wireless++ }, usb = { wired++ }, settings = {}, disconnect = {},
            choosePhone = {}, hotspotSettings = {},
        )).build(content, compact = true, setupError = null, hotspotOff = false, version = "test")
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val views = descendants(content).toList()
        assertEquals(3, views.count { it is NissanHomeArtworkView })
        val labels = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains(activity.getString(R.string.home_wireless_connection)))
        assertTrue(labels.contains(activity.getString(R.string.home_wired_connection)))
        assertFalse(labels.contains(activity.getString(R.string.a_familiar_drive)))
        widgets.connect.performClick()
        views.filterIsInstance<Button>().first {
            it.text == activity.getString(R.string.home_usb_connect)
        }.performClick()
        assertEquals(1, wireless)
        assertEquals(1, wired)
        assertEquals(View.GONE, widgets.disconnect.visibility)
    }
}
