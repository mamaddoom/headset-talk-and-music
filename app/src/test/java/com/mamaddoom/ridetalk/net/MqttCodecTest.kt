package com.mamaddoom.ridetalk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class MqttCodecTest {

    @Test
    fun remainingLengthMatchesSpecExamples() {
        assertArrayEquals(byteArrayOf(0), MqttCodec.remainingLength(0))
        assertArrayEquals(byteArrayOf(127), MqttCodec.remainingLength(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), MqttCodec.remainingLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), MqttCodec.remainingLength(16383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 1), MqttCodec.remainingLength(16384))
    }

    @Test
    fun publishRoundTrips() {
        val payload = ByteArray(700) { it.toByte() }
        val wire = MqttCodec.publishHeader("hm1/abc", payload.size) + payload
        val p = MqttCodec.read(DataInputStream(ByteArrayInputStream(wire)))
        assertEquals(MqttCodec.PUBLISH, p.type)
        val (topic, body) = MqttCodec.parsePublish(p)!!
        assertEquals("hm1/abc", topic)
        assertArrayEquals(payload, body)
    }

    @Test
    fun connectPacketIsWellFormed() {
        val c = MqttCodec.connect("hm-1", 30, "public", "public")
        assertEquals(0x10, c[0].toInt() and 0xFF)
        val p = MqttCodec.read(DataInputStream(ByteArrayInputStream(c)))
        // Protocol name "MQTT", level 4, flags: user + password + clean session.
        assertEquals("MQTT", String(p.body, 2, 4))
        assertEquals(4, p.body[6].toInt())
        assertEquals(0xC2, p.body[7].toInt() and 0xFF)
    }

    @Test
    fun channelIdDependsOnCodeAndHidesIt() {
        val a = PacketCrypto("sunset-ride-2026", 1000, PacketCrypto.ONLINE_SALT)
        val b = PacketCrypto("sunset-ride-2027", 1000, PacketCrypto.ONLINE_SALT)
        assertEquals(32, a.channelId.length)
        assertNotEquals(a.channelId, b.channelId)
        assertEquals(a.channelId, PacketCrypto("sunset-ride-2026", 1000, PacketCrypto.ONLINE_SALT).channelId)
    }
}
