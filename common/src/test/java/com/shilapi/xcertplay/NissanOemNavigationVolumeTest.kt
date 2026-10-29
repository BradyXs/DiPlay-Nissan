package com.shilapi.xcertplay

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NissanOemNavigationVolumeTest {
    @Test fun readsAndWritesOnlyTheFactoryNavigationVolumeType() {
        var current = 4
        val settings = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.hsae.autosdk.settings.IAutoSettings")
                assertEquals(10, data.readInt())
                when (code) {
                    31 -> current = data.readInt()
                    32 -> Unit
                    else -> error("unexpected transaction $code")
                }
                reply?.writeNoException()
                if (code == 32) reply?.writeInt(current)
                return true
            }
        }
        assertEquals(4, NissanOemNavigationVolume.readLevel(settings))
        NissanOemNavigationVolume.setLevel(settings, 11)
        assertEquals(11, NissanOemNavigationVolume.readLevel(settings))
    }
}
