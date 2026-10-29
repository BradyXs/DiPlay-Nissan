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
class NissanOemPhoneVolumeTest {
    @Test fun readsAndWritesOnlyTheFactoryPhoneLevel() {
        var current = 10
        val settings = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.hsae.autosdk.settings.IAutoSettings")
                when (code) {
                    33 -> current = data.readInt()
                    34 -> Unit
                    else -> error("unexpected transaction $code")
                }
                reply?.writeNoException()
                if (code == 34) reply?.writeInt(current)
                return true
            }
        }
        assertEquals(10, NissanOemPhoneVolume.readLevel(settings))
        NissanOemPhoneVolume.setLevel(settings, 15)
        assertEquals(15, NissanOemPhoneVolume.readLevel(settings))
    }
}
