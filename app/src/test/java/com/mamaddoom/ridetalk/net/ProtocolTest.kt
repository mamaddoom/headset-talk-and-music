package com.mamaddoom.ridetalk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ProtocolTest {
    private fun wrap(buf: ByteBuffer, len: Int): ByteBuffer = ByteBuffer.wrap(buf.array(), 0, len)

    @Test
    fun beaconRoundTrip() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val len = Protocol.encodeBeacon(buf, 1234, 7, Protocol.Beacon(Protocol.FLAG_MUSIC, "علی", "آهنگ خوب"))
        val rx = wrap(buf, len)
        val h = Protocol.readHeader(rx)!!
        assertEquals(Protocol.TYPE_BEACON, h.type)
        assertEquals(1234, h.sender)
        assertEquals(7, h.seq)
        val b = Protocol.decodeBeacon(rx)!!
        assertEquals(Protocol.FLAG_MUSIC, b.flags)
        assertEquals("علی", b.name)
        assertEquals("آهنگ خوب", b.title)
    }

    @Test
    fun voiceBytesRoundTrip() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val opus = ByteArray(57) { it.toByte() }
        val len = Protocol.encodeBytes(buf, Protocol.TYPE_VOICE, -5, Int.MAX_VALUE, opus, opus.size)
        assertEquals(Protocol.HEADER + 57, len)
        val rx = wrap(buf, len)
        val h = Protocol.readHeader(rx)!!
        assertEquals(-5, h.sender)
        assertEquals(Int.MAX_VALUE, h.seq)
        assertArrayEquals(opus, Protocol.decodeBytes(rx))
    }

    @Test
    fun musicPacketFitsOneMtuAndRoundTrips() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val pcm = ShortArray(Protocol.MUSIC_SAMPLES) { (it * 37 - 9000).toShort() }
        val len = Protocol.encodePcm(buf, Protocol.TYPE_MUSIC, 9, 3, pcm, pcm.size)
        assertTrue("music packet must fit a 1472-byte UDP payload", len <= 1472)
        val rx = wrap(buf, len)
        Protocol.readHeader(rx)!!
        assertArrayEquals(pcm, Protocol.decodePcm(rx, Protocol.MUSIC_SAMPLES))
    }

    @Test
    fun rejectsForeignAndTruncatedPackets() {
        assertNull(Protocol.readHeader(ByteBuffer.wrap("hello world!".toByteArray())))
        assertNull(Protocol.readHeader(ByteBuffer.wrap(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 1))))
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val len = Protocol.encodePcm(buf, Protocol.TYPE_MUSIC, 1, 1, ShortArray(10), 10)
        val rx = wrap(buf, len)
        assertNotNull(Protocol.readHeader(rx))
        assertNull("short payload must not decode", Protocol.decodePcm(rx, Protocol.MUSIC_SAMPLES))
        // Wrong version
        val bad = buf.array().copyOf(len).also { it[2] = 99 }
        assertNull(Protocol.readHeader(ByteBuffer.wrap(bad)))
    }

    @Test
    fun longNamesAreTruncatedOnCharacterBoundary() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val name = "س".repeat(50) // 2 bytes each in UTF-8
        val len = Protocol.encodeBeacon(buf, 1, 1, Protocol.Beacon(0, name, ""))
        val rx = wrap(buf, len)
        Protocol.readHeader(rx)
        val b = Protocol.decodeBeacon(rx)!!
        assertEquals("س".repeat(20), b.name)
    }
}
