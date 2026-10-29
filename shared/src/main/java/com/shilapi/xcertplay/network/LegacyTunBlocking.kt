package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import java.io.IOException

/** API 19 has no VpnService.Builder.setBlocking; update the authorized TUN fd itself. */
internal object LegacyTunBlocking {
    init { System.loadLibrary("usb_host_compat") }

    fun enable(tun: ParcelFileDescriptor) {
        val errno = setBlocking(tun.fd)
        if (errno != 0) throw IOException("Could not make legacy TUN blocking: errno=$errno")
    }

    private external fun setBlocking(fd: Int): Int
}
