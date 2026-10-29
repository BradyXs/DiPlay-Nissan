package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarPlayVpnService
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Dalvik/native/socket/AudioTrack tests, not an iPhone or a T3 kernel simulation. */
class T3LegacyRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun settingsOpensEveryDriverCategoryOnKitKat() {
        val activity = instrumentation.startActivitySync(Intent(context, DiPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val settings = texts(activity.window.decorView).first { it.text.toString() == "Settings" }
                assertTrue(settings.performClick())
            }
            instrumentation.waitForIdleSync()
            for (title in listOf("Connection", "Display", "Audio", "Navigation", "Vehicle", "Diagnostics", "Advanced")) {
                instrumentation.runOnMainSync {
                    val row = texts(activity.window.decorView).firstOrNull { it.text.toString() == title }
                    assertNotNull("Missing category $title", row)
                    var target: View = row!!
                    while (!target.isClickable && target.parent is View) target = target.parent as View
                    assertTrue("Open $title", target.performClick())
                }
                instrumentation.waitForIdleSync()
                assertFalse(activity.isFinishing)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun nativeTunBlockingUsesAuthorizedFdOnKitKat() {
        val type = Class.forName("com.shilapi.xcertplay.network.LegacyTunBlocking")
        val owner = type.getField("INSTANCE").get(null)
        val pipes = ParcelFileDescriptor.createPipe()
        try {
            type.getMethod("enable", ParcelFileDescriptor::class.java).invoke(owner, pipes[0])
        } finally { pipes.forEach { it.close() } }
    }

    @Test fun connectionEntryScreensLaunchAndCloseWithoutModernApis() {
        val modes = listOf(com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL,
            com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P, null)
        for (mode in modes) {
            AirPlayPersistence.saveWirelessEnabled(context, mode != null)
            if (mode != null) AirPlayPersistence.saveWirelessHotspotMode(context, mode)
            AirPlayPersistence.saveManualHotspotSsid(context, "T3 runtime test")
            AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
            val activity = instrumentation.startActivitySync(Intent(context, CarPlayHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                assertFalse("Connection entry $mode finished unexpectedly", activity.isFinishing)
                assertTrue("Connection entry $mode has no content", texts(activity.window.decorView).isNotEmpty())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
    }

    @Test fun pairingCryptoRoundTripsOnDalvik() {
        val first = AirPlayCrypto.x25519Generate()
        val second = AirPlayCrypto.x25519Generate()
        assertArrayEquals(AirPlayCrypto.x25519Shared(first.privateKey, second.publicKey),
            AirPlayCrypto.x25519Shared(second.privateKey, first.publicKey))
        val signing = AirPlayCrypto.ed25519Generate()
        val payload = byteArrayOf(1, 2, 3, 4)
        assertTrue(AirPlayCrypto.ed25519Verify(signing.publicKey, payload,
            AirPlayCrypto.ed25519Sign(signing.privateKey, payload)))
        val key = AirPlayCrypto.hkdfSha512(first.privateKey, byteArrayOf(5), byteArrayOf(6))
        val nonce = AirPlayCrypto.nonce64(1)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload)
        assertArrayEquals(payload, AirPlayCrypto.chachaOpen(key, nonce, sealed))
    }

    @Test fun bundledAuthenticationLoadsAndSignsLocally() {
        val bootstrap = Class.forName("com.shilapi.xcertplay.DiPlayBootstrap")
        bootstrap.getMethod("ensure", Context::class.java, com.shilapi.xcertplay.orchestration.MfiTarget::class.java)
            .invoke(bootstrap.getField("INSTANCE").get(null), context, com.shilapi.xcertplay.orchestration.MfiTarget.LOCAL)
        val root = if (Build.VERSION.SDK_INT >= 21) context.noBackupFilesDir else context.filesDir
        val client = com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.load(java.io.File(root, "offline-mfi"))
        assertTrue(client.readCertificate(16384).isNotEmpty())
        assertEquals(64, client.signChallenge(ByteArray(32)).size)
    }

    @Test fun kitKatUsbDescriptorAdapterPreservesRealConfigurationAndAlternates() {
        if (Build.VERSION.SDK_INT >= 21) return
        val type = Class.forName("com.shilapi.xcertplay.transport.KitKatUsbDescriptors")
        val owner = type.getField("INSTANCE").get(null)
        val raw = intArrayOf(9,2,32,0,1,6,0,128,50, 9,4,3,1,2,10,0,0,0,
            7,5,0x81,2,0,2,0, 7,5,2,2,0,2,0).map { it.toByte() }.toByteArray()
        val configs = type.getMethod("parse", ByteArray::class.java).invoke(owner, raw) as List<*>
        val config = configs.single()!!
        assertEquals(6, config.javaClass.getMethod("getId").invoke(config))
        val info = (config.javaClass.getMethod("getInterfaces").invoke(config) as List<*>).single()!!
        val adapter = type.methods.single { it.name.startsWith("platformInterface") }
        val iface = adapter.invoke(owner, info) as UsbInterface
        assertEquals(3, iface.id)
        assertEquals(1, IphoneAlternate(iface))
        assertEquals(2, iface.endpointCount)
        assertEquals(512, iface.getEndpoint(0).maxPacketSize)
    }

    @Test fun pcmAudioStartsWritesAndClosesWithLegacyFocus() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = true, mediaBufferMillis = 60,
            onAudioDiagnostic = { line ->
                lines += line
                if (line.startsWith("Audio: ready")) ready.countDown()
                if (line.contains("first PCM")) started.countDown()
            })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96)
        try {
            sink.onAudioStarted(id, format, 0)
            assertTrue("AudioTrack initialization: $lines", ready.await(5, TimeUnit.SECONDS))
            repeat(40) { packet ->
                sink.onAudioRtp(id, format, ByteArray(12 + 1920).apply { this[0] = 0x80.toByte() }, packet * 480)
            }
            assertTrue("PCM write path: $lines", started.await(5, TimeUnit.SECONDS))
            assertFalse("Renderer failure: $lines", lines.any { it.contains("renderer failed") })
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_GAIN)
        } finally { sink.onAudioStopped(id); sink.close() }
    }

    @Test fun h264FirstFrameRendersAndDecoderReleasesOnKitKat() {
        val texture = android.graphics.SurfaceTexture(1)
        val surface = android.view.Surface(texture)
        val rendered = CountDownLatch(1)
        val frameAvailable = CountDownLatch(1)
        texture.setOnFrameAvailableListener { frameAvailable.countDown() }
        val sink = AndroidMediaSink(surface = surface, videoWidth = 320, videoHeight = 240)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        sink.setVideoDiagnosticHandler(0) { line ->
            lines += line
            if (line.contains("first frame rendered")) rendered.countDown()
        }
        val encoder = android.media.MediaCodec.createEncoderByType("video/avc")
        var encoderStarted = false
        try {
            val format = android.media.MediaFormat.createVideoFormat("video/avc", 320, 240).apply {
                setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 500000)
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val info = android.media.MediaCodec.BufferInfo()
            var submitted = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && rendered.count > 0) {
                if (submitted < 10) {
                    val input = encoder.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val pixels = ByteArray(320 * 240 * 3 / 2) { 128.toByte() }
                        encoder.inputBuffers[input].apply { clear(); put(pixels) }
                        encoder.queueInputBuffer(input, 0, pixels.size, submitted * 33333L, 0)
                        submitted++
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10000)
                if (output == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    fun parameter(name: String): ByteArray {
                        val buffer = encoder.outputFormat.getByteBuffer(name)!!.duplicate()
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        val prefix = if (bytes[2] == 1.toByte()) 3 else 4
                        return bytes.copyOfRange(prefix, bytes.size)
                    }
                    val sps = parameter("csd-0")
                    val pps = parameter("csd-1")
                    val avc = byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(),
                        (sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
                        byteArrayOf(1, (pps.size ushr 8).toByte(), pps.size.toByte()) + pps
                    sink.onVideoCodec(0, VideoCodec.H264)
                    sink.onVideoConfig(0, avc)
                } else if (output >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = encoder.outputBuffers[output].duplicate().apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        sink.onVideoFrame(0, carPlayAccessUnit(ByteArray(info.size).also(buffer::get)))
                    }
                    encoder.releaseOutputBuffer(output, false)
                }
            }
            assertTrue("First decoded frame: $lines", rendered.await(2, TimeUnit.SECONDS))
            assertTrue("Surface received no frame: $lines", frameAvailable.await(2, TimeUnit.SECONDS))
        } finally {
            if (encoderStarted) encoder.stop()
            encoder.release()
            sink.close()
            val released = sink.awaitVideoReleased(3000)
            surface.release()
            texture.release()
            assertTrue("Video decoder did not release", released)
        }
    }

    @Test fun airPlayListenerAnswersInfoAndRecoversOccupiedPortTwice() {
        val bound = CountDownLatch(1)
        var service: CarPlayVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as CarPlayVpnService.LocalBinder).service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, CarPlayVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            val address = InetAddress.getByName("127.0.0.1")
            ServerSocket(0, 1, address).use { occupied ->
                repeat(2) {
                    val result = service!!.attachWireless(address,
                        AirPlayConfig(deviceName = "T3 KitKat runtime test", deviceId = "02:00:00:00:00:02",
                            btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
                            main = AirPlayDisplayConfig(1024,600,fps=30), port = occupied.localPort),
                        AirPlayIdentity.generate(), PairingStore(), null,
                        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
                    assertTrue("Startup: $result", result is CarPlayVpnService.AttachResult.Started)
                    val port = service!!.boundPort()!!
                    assertNotEquals(occupied.localPort, port)
                    val client = URL("http://127.0.0.1:$port/info").openConnection() as HttpURLConnection
                    client.connectTimeout = 5000; client.readTimeout = 5000
                    try {
                        assertEquals(200, client.responseCode)
                        val plist = client.inputStream.use { BplistCodec.decode(it.readBytes()) } as Map<*, *>
                        assertEquals("T3 KitKat runtime test", plist["name"])
                    } finally { client.disconnect() }
                    service!!.detach()
                    assertFalse(service!!.isAttached())
                }
            }
        } finally { service?.detach(); context.unbindService(connection) }
    }

    // Android's encoder emits Annex B; CarPlay supplies length-prefixed NAL units.
    private fun carPlayAccessUnit(annexB: ByteArray): ByteArray {
        val units = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        while (cursor + 3 < annexB.size) {
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                annexB[cursor + 2] == 1.toByte() -> 3
                annexB[cursor + 2] == 0.toByte() && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) {
                units += cursor to cursor + prefix
                cursor += prefix
            } else cursor++
        }
        val output = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(output)
        if (units.isEmpty()) {
            // KitKat's software AVC encoder can return a single raw slice NAL.
            check(annexB.isNotEmpty() && (annexB[0].toInt() and 31) in listOf(1, 5)) {
                "Unexpected encoder access unit, bytes=${annexB.size}, header=${annexB.firstOrNull()}"
            }
            data.writeInt(annexB.size)
            data.write(annexB)
            return output.toByteArray()
        }
        units.forEachIndexed { index, (_, start) ->
            val end = units.getOrNull(index + 1)?.first ?: annexB.size
            check(end > start)
            data.writeInt(end - start)
            data.write(annexB, start, end - start)
        }
        return output.toByteArray()
    }

    private fun IphoneAlternate(iface: UsbInterface): Int =
        com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration.alternateSetting(iface)

    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
}
