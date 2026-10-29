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
    fun redundancyContainerRoundTrips() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val frames = listOf(ByteArray(61) { 1 }, ByteArray(58) { 2 }, ByteArray(64) { 3 })
        val len = Protocol.encodeFrames(buf, Protocol.TYPE_VOICE, -5, Int.MAX_VALUE, frames)
        val rx = wrap(buf, len)
        val h = Protocol.readHeader(rx)!!
        assertEquals(-5, h.sender)
        assertEquals(Int.MAX_VALUE, h.seq)
        val got = Protocol.decodeFrames(rx)!!
        assertEquals(3, got.size)
        for (k in frames.indices) assertArrayEquals(frames[k], got[k])
    }

    @Test
    fun containerNeverOverflowsADatagram() {
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        // Two maximum-size music frames fit; a third does not and is left out.
        val frames = List(3) { ByteArray(640) { 9 } }
        val len = Protocol.encodeFrames(buf, Protocol.TYPE_MUSIC, 1, 1, frames)
        assertTrue("datagram is $len bytes", len + 16 <= 1472)
        val rx = wrap(buf, len)
        Protocol.readHeader(rx)!!
        assertEquals("newest frames kept", 2, Protocol.decodeFrames(rx)!!.size)
    }

    @Test
    fun rejectsForeignTruncatedAndOldVersionPackets() {
        assertNull(Protocol.readHeader(ByteBuffer.wrap("hello world!".toByteArray())))
        assertNull(Protocol.readHeader(ByteBuffer.wrap(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 2))))
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        val len = Protocol.encodeFrames(buf, Protocol.TYPE_VOICE, 1, 1, listOf(ByteArray(40)))
        val truncated = wrap(buf, len - 10)
        assertNotNull(Protocol.readHeader(truncated))
        assertNull("short payload must not decode", Protocol.decodeFrames(truncated))
        // Packets from v1 builds are ignored, not misread.
        val old = buf.array().copyOf(len).also { it[2] = 1 }
        assertNull(Protocol.readHeader(ByteBuffer.wrap(old)))
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
