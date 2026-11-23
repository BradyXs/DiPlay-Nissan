package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairVerifyPreparationTest {
    @Test fun warmKeyIsConsumedOnceAndItsPrivateBytesAreClearedAfterM2() {
        val preparation = PairVerifyPreparation()
        assertTrue(preparation.prepare())
        val prepared = preparation.take()!!
        assertNull(preparation.take())
        assertFalse(preparation.prepare())

        val phone = AirPlayCrypto.x25519Generate()
        val verify = PairVerify(AirPlayIdentity.generate(), PairingStore()) { prepared }
        val response = Tlv8Codec.decode(verify.handle(Tlv8Codec.encode(listOf(
            Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, phone.publicKey),
        ))))
        assertArrayEquals(byteArrayOf(2), response[6])
        assertArrayEquals(prepared.publicKey, response[3])
        assertNotNull(response[5])
        assertArrayEquals(ByteArray(32), prepared.privateKey)
        phone.privateKey.fill(0)
    }

    @Test fun noWaitWhenHandshakeOrCloseWinsTheRace() {
        val unprepared = PairVerifyPreparation()
        assertNull(unprepared.take())
        assertFalse(unprepared.prepare())
        val closed = PairVerifyPreparation()
        assertTrue(closed.prepare())
        closed.close()
        assertNull(closed.take())
    }
}
