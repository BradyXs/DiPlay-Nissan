package com.shilapi.xcertplay

import android.content.Intent
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class DiPlayPageHostTest {
    @Test fun openingSettingsSearchDoesNotReplaceTheVisiblePage() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            val host = ReflectionHelpers.getField<DiPlayPageHost>(activity, "pageHost")
            val visiblePage = host.getChildAt(0)
            ReflectionHelpers.callInstanceMethod<List<*>>(activity, "buildSettingsSearchIndex")
            assertSame(visiblePage, host.getChildAt(0))
            assertEquals(1, host.childCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun rebuildingSettingsKeepsTheWindowRootAndOneVisiblePage() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            val host = ReflectionHelpers.getField<DiPlayPageHost>(activity, "pageHost")
            val oldPage = host.getChildAt(0)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            assertSame(host, ReflectionHelpers.getField<DiPlayPageHost>(activity, "pageHost"))
            assertEquals(1, host.childCount)
            assertNotSame(oldPage, host.getChildAt(0))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun changingSettingsCategoryKeepsThePageAndScrollContainers() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            val host = ReflectionHelpers.getField<DiPlayPageHost>(activity, "pageHost")
            val page = host.getChildAt(0)
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            val rail = ReflectionHelpers.getField<ScrollView>(activity, "settingsRailScroll")
            val previousBody = ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "settingsBody")
            (rail.getChildAt(0) as ViewGroup).getChildAt(SettingsCategory.AUDIO.ordinal).performClick()
            assertSame(page, host.getChildAt(0))
            assertSame(scroll, ReflectionHelpers.getField<ScrollView>(activity, "rootScroll"))
            assertSame(rail, ReflectionHelpers.getField<ScrollView>(activity, "settingsRailScroll"))
            assertNotSame(previousBody, ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "settingsBody"))
            assertEquals(1, host.childCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
