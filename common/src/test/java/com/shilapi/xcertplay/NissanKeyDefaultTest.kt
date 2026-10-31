package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.host.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NissanKeyDefaultTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val component by lazy { ComponentName(context, NissanPhoneKeyProbeService::class.java) }

    @After fun cleanup() {
        NissanFirmwareHmiKeys.stop()
        context.getSharedPreferences("nissan_phone_key_probe", Context.MODE_PRIVATE).edit().clear().commit()
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    }

    @Test fun newInstallDefaultsToTheBuildProfileAndPreservesAnExplicitDisable() {
        context.getSharedPreferences("nissan_phone_key_probe", Context.MODE_PRIVATE).edit().clear().commit()
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        NissanPhoneKeyProbe.ensureEnabled(context)
        if (BuildConfig.NISSAN_ANDROID_44_PROFILE) {
            assertTrue(NissanPhoneKeyProbe.enabled(context))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                context.packageManager.getComponentEnabledSetting(component))
            context.getSharedPreferences("nissan_phone_key_probe", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", false).commit()
            NissanPhoneKeyProbe.ensureEnabled(context)
            assertFalse(NissanPhoneKeyProbe.enabled(context))
        } else {
            assertFalse(NissanPhoneKeyProbe.enabled(context))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                context.packageManager.getComponentEnabledSetting(component))
        }
    }
}
