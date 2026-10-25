package com.mamaddoom.ridetalk.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format (little endian, one UDP datagram per packet):
 *
 *   0  u8[2]  magic "RT"
 *   2  u8     version
 *   3  u8     type
 *   4  i32    sender id (random per app session)
 *   8  i32    sequence number (per sender, per stream)
 *  12  ...    payload
 *
 * Voice payload is one Opus packet (16 kHz mono, 20 ms, in-band FEC).
 * Music payload is raw PCM16: on a hotspot 1.3 Mbit/s is nothing, and skipping
 * a codec keeps music CPU cost at zero on both phones.
 */
object Protocol {
    const val PORT = 47800
    const val VERSION: Byte = 1
    const val HEADER = 12
    private const val MAGIC0: Byte = 'R'.code.toByte()
    private const val MAGIC1: Byte = 'T'.code.toByte()

    const val TYPE_BEACON: Byte = 1
    const val TYPE_VOICE: Byte = 2
    const val TYPE_MUSIC: Byte = 3
    const val TYPE_BYE: Byte = 4

    /** Voice: 16 kHz mono, 20 ms frames. */
    const val VOICE_RATE = 16000
    const val VOICE_FRAME = 320

    /** Music: 32 kHz stereo, 10 ms packets (1280 bytes, fits one MTU). */
    const val MUSIC_RATE = 32000
    const val MUSIC_FRAMES = 320
    const val MUSIC_SAMPLES = MUSIC_FRAMES * 2

    const val FLAG_TALKING = 1
    const val FLAG_MUSIC = 2

    const val MAX_PACKET = 1500
    private const val MAX_NAME = 40
    private const val MAX_TITLE = 120

    class Header(val type: Byte, val sender: Int, val seq: Int)

    class Beacon(val flags: Int, val name: String, val title: String)

    fun writeHeader(buf: ByteBuffer, type: Byte, sender: Int, seq: Int) {
        buf.clear()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC0).put(MAGIC1).put(VERSION).put(type)
        buf.putInt(sender).putInt(seq)
    }

    /** Returns null if the datagram is not ours. Leaves [buf] positioned at the payload. */
    fun readHeader(buf: ByteBuffer): Header? {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < HEADER) return null
        if (buf.get() != MAGIC0 || buf.get() != MAGIC1 || buf.get() != VERSION) return null
        val type = buf.get()
        val sender = buf.getInt()
        val seq = buf.getInt()
        return Header(type, sender, seq)
    }

    fun encodeBeacon(buf: ByteBuffer, sender: Int, seq: Int, b: Beacon): Int {
        writeHeader(buf, TYPE_BEACON, sender, seq)
        buf.put(b.flags.toByte())
        putString(buf, b.name, MAX_NAME)
        putString(buf, b.title, MAX_TITLE)
        return buf.position()
    }

    fun decodeBeacon(buf: ByteBuffer): Beacon? {
        if (buf.remaining() < 1) return null
        val flags = buf.get().toInt() and 0xff
        val name = getString(buf) ?: return null
        val title = getString(buf) ?: return null
        return Beacon(flags, name, title)
    }

    fun encodeBytes(buf: ByteBuffer, type: Byte, sender: Int, seq: Int, data: ByteArray, len: Int): Int {
        writeHeader(buf, type, sender, seq)
        buf.put(data, 0, len)
        return buf.position()
    }

    fun decodeBytes(buf: ByteBuffer): ByteArray? {
        if (!buf.hasRemaining()) return null
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    fun encodePcm(buf: ByteBuffer, type: Byte, sender: Int, seq: Int, pcm: ShortArray, count: Int): Int {
        writeHeader(buf, type, sender, seq)
        buf.asShortBuffer().put(pcm, 0, count)
        return HEADER + count * 2
    }

    /** Decodes exactly [count] samples, or returns null if the payload is short. */
    fun decodePcm(buf: ByteBuffer, count: Int): ShortArray? {
        if (buf.remaining() < count * 2) return null
        val out = ShortArray(count)
        buf.asShortBuffer().get(out)
        return out
    }

    fun encodeBye(buf: ByteBuffer, sender: Int): Int {
        writeHeader(buf, TYPE_BYE, sender, 0)
        return HEADER
    }

    private fun putString(buf: ByteBuffer, s: String, maxBytes: Int) {
        var bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) bytes = truncateUtf8(bytes, maxBytes)
        buf.put(bytes.size.toByte())
        buf.put(bytes)
    }

    private fun getString(buf: ByteBuffer): String? {
        if (buf.remaining() < 1) return null
        val len = buf.get().toInt() and 0xff
        if (buf.remaining() < len) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Cuts at a UTF-8 character boundary so Persian text never ends in a broken glyph. */
    internal fun truncateUtf8(bytes: ByteArray, max: Int): ByteArray {
        var end = max
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }
}
