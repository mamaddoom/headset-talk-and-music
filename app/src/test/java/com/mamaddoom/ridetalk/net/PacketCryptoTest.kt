package com.mamaddoom.ridetalk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PacketCryptoTest {
    private fun voicePacket(seq: Int): Pair<ByteArray, Int> {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val payload = ByteArray(60) { (it * 7 + seq).toByte() }
        val len = Protocol.encodeBytes(buf, Protocol.TYPE_VOICE, 77, seq, payload, payload.size)
        return buf.array().copyOf(len) to len
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val c = PacketCrypto("ride2026")
        val (plain, len) = voicePacket(5)
        val wire = ByteArray(Protocol.MAX_PACKET + 64)
        val n = c.seal(plain, len, wire)
        assertEquals(len + PacketCrypto.OVERHEAD, n)
        assertTrue(PacketCrypto.isSealed(wire, n))
        assertFalse("payload must not travel in clear", wire.copyOfRange(12, 72).contentEquals(plain.copyOfRange(12, 72)))

        val opened = PacketCrypto("ride2026").open(wire, n, ByteArray(wire.size))
        assertEquals(len, opened)
        assertArrayEquals(plain, wire.copyOf(len))
        val h = Protocol.readHeader(ByteBuffer.wrap(wire, 0, len))!!
        assertEquals(Protocol.TYPE_VOICE, h.type)
        assertEquals(5, h.seq)
    }

    @Test
    fun wrongCodeAndTamperingAreRejected() {
        val (plain, len) = voicePacket(9)
        val wire = ByteArray(Protocol.MAX_PACKET + 64)
        val n = PacketCrypto("ride2026").seal(plain, len, wire)

        assertEquals(-1, PacketCrypto("other").open(wire.copyOf(), n, ByteArray(wire.size)))

        val tampered = wire.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertEquals(-1, PacketCrypto("ride2026").open(tampered, n, ByteArray(wire.size)))

        val headerTampered = wire.copyOf().also { it[8] = (it[8] + 1).toByte() } // sequence number
        assertEquals(-1, PacketCrypto("ride2026").open(headerTampered, n, ByteArray(wire.size)))
    }

    @Test
    fun largestMusicPacketStillFitsOneDatagram() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val len = Protocol.encodePcm(buf, Protocol.TYPE_MUSIC, 1, 1, ShortArray(Protocol.MUSIC_SAMPLES), Protocol.MUSIC_SAMPLES)
        val wire = ByteArray(Protocol.MAX_PACKET + 64)
        val n = PacketCrypto("x").seal(buf.array(), len, wire)
        assertTrue("sealed music packet is $n bytes", n <= 1472)
    }
}
