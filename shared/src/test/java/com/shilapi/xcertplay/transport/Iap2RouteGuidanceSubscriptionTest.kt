package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RouteGuidanceSubscriptionTest {
    @Test
    fun laneGuidanceIsDeclaredOnlyWhenEnabled() {
        val base = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)
        fun received(config: Iap2IdentificationConfig) =
            Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(config)).u16List(7)
        assertFalse(0x5204 in received(base))
        assertTrue(0x5204 in received(base.copy(laneGuidanceEnabled = true)))
    }
    @Test
    fun identificationDeclaresRouteGuidanceDisplayComponent() {
        val config = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = "xcertplay",
            manufacturer = "xcertplay",
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
        )

        val parameters = Iap2ParameterList.parse(
            Iap2IdentificationClient.identificationInformation(config).payload,
        ).asList()
        val component = Iap2ParameterList.parse(parameters.single { it.id == 30 }.payload).asList()
        assertArrayEquals(byteArrayOf(0, 42), component.single { it.id == 0 }.payload)
        assertArrayEquals("RouteGuidance\u0000".encodeToByteArray(), component.single { it.id == 1 }.payload)
        assertArrayEquals(byteArrayOf(0, 8), component.single { it.id == 6 }.payload)
    }

    @Test
    fun artworkOptOutOnlyRemovesTheArtworkFieldFromNowPlaying() {
        val standard = Iap2ControlMessages.subscriptions()
        val withoutArtwork = Iap2ControlMessages.subscriptions(includeArtwork = false)
        assertEquals(standard.map { it.messageId }, withoutArtwork.map { it.messageId })
        for ((original, filtered) in standard.zip(withoutArtwork)) {
            if (original.messageId != 0x5000) {
                assertArrayEquals(original.payload, filtered.payload)
                continue
            }
            fun mediaFields(payload: ByteArray) = Iap2ParameterList.parse(
                Iap2ParameterList.parse(payload).asList().single { it.id == 0 }.payload,
            ).asList().map { it.id }
            assertEquals(listOf(1, 4, 6, 12, 26), mediaFields(original.payload))
            assertEquals(listOf(1, 4, 6, 12), mediaFields(filtered.payload))
            val playbackGroup = { payload: ByteArray ->
                Iap2ParameterList.parse(payload).asList().single { it.id == 1 }.payload
            }
            assertArrayEquals(playbackGroup(original.payload), playbackGroup(filtered.payload))
        }
    }

    @Test
    fun startRouteGuidanceUpdatesRequestsDeclaredComponent() {
        val frame = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }

        assertEquals(0x5200, frame.messageId)
        assertArrayEquals(
            byteArrayOf(0, 6, 0, 0, 0, 42, 0, 4, 0, 1, 0, 4, 0, 2),
            frame.payload,
        )
    }
}
