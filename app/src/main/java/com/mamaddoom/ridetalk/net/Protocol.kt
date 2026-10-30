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
 * Voice and music payloads are a redundancy container (in the spirit of RFC 2198):
 *
 *   u8 count, then count × (u16 length, Opus packet), newest first;
 *   entry k belongs to sequence number (seq - k).
 *
 * Voice carries the current 20 ms Opus frame plus the two before it (with
 * Opus in-band FEC on top), music the current 20 ms frame plus one. A
 * packet lost on a fading highway link is rebuilt from the next one, and
 * compressed music (~128 kbit/s instead of 1.3 Mbit/s raw) leaves the weak
 * link enough airtime for voice and keepalives.
 */
object Protocol {
    const val PORT = 47800
    /** v2: redundancy containers and Opus music. Older builds are ignored, not misread. */
    const val VERSION: Byte = 2
    const val HEADER = 12
    private const val MAGIC0: Byte = 'R'.code.toByte()
    private const val MAGIC1: Byte = 'T'.code.toByte()

    const val TYPE_BEACON: Byte = 1
    const val TYPE_VOICE: Byte = 2
    const val TYPE_MUSIC: Byte = 3
    const val TYPE_BYE: Byte = 4
    /**
     * Header-only packet sent every 40 ms while nothing else is flowing: keeps
     * both Wi-Fi radios out of power-save, which otherwise holds incoming
     * packets for 100–300 ms and makes the first words after a pause late.
     */
    const val TYPE_KEEPALIVE: Byte = 5

    /** Voice: 16 kHz mono, 20 ms frames. */
    const val VOICE_RATE = 16000
    const val VOICE_FRAME = 320

    /** Music: 48 kHz stereo, 20 ms Opus frames. */
    const val MUSIC_RATE = 48000
    const val MUSIC_FRAMES = 960
    const val MUSIC_SAMPLES = MUSIC_FRAMES * 2

    /** Redundancy depth (frames per packet, including the current one). */
    const val VOICE_REDUNDANCY = 3
    const val MUSIC_REDUNDANCY = 2

    const val FLAG_TALKING = 1
    const val FLAG_MUSIC = 2
    /** Two-phone setup roles: the rider's intercom phone / music-only phone. */
    const val FLAG_TALK_PHONE = 4
    const val FLAG_MUSIC_PHONE = 8
    /** This rider is on a phone call: intercom paused, back when the call ends. */
    const val FLAG_ON_CALL = 16

    /** Beacon music-loss byte: not receiving music. */
    const val LOSS_UNKNOWN = 255

    const val MAX_PACKET = 1500
    /** Largest datagram we build: 1472 bytes of UDP payload minus room for the group-code tag. */
    private const val MAX_PAYLOAD_END = 1472 - 16
    private const val MAX_NAME = 40
    private const val MAX_TITLE = 120

    class Header(val type: Byte, val sender: Int, val seq: Int)

    /**
     * [musicLoss]: percent of shared-music frames this rider lost lately, or
     * [LOSS_UNKNOWN]. Lets the music sender lower its bitrate on a weak link.
     * Sent as a trailing byte, which older builds simply ignore.
     */
    class Beacon(val flags: Int, val name: String, val title: String, val musicLoss: Int = LOSS_UNKNOWN)

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
        buf.put(b.musicLoss.coerceIn(0, 255).toByte())
        return buf.position()
    }

    fun decodeBeacon(buf: ByteBuffer): Beacon? {
        if (buf.remaining() < 1) return null
        val flags = buf.get().toInt() and 0xff
        val name = getString(buf) ?: return null
        val title = getString(buf) ?: return null
        val loss = if (buf.hasRemaining()) buf.get().toInt() and 0xff else LOSS_UNKNOWN
        return Beacon(flags, name, title, loss)
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

    /**
     * Writes a redundancy container: [frames] newest first (frames[0] is [seq]).
     * Stops early rather than overflowing one datagram, so the newest frame always fits.
     */
    fun encodeFrames(buf: ByteBuffer, type: Byte, sender: Int, seq: Int, frames: List<ByteArray>): Int {
        writeHeader(buf, type, sender, seq)
        val countPos = buf.position()
        buf.put(0)
        var count = 0
        for (f in frames) {
            if (count == 255 || buf.position() + 2 + f.size > MAX_PAYLOAD_END) break
            buf.putShort(f.size.toShort())
            buf.put(f)
            count++
        }
        buf.put(countPos, count.toByte())
        return buf.position()
    }

    /** Reads a redundancy container; entry k belongs to (seq - k). Null if malformed. */
    fun decodeFrames(buf: ByteBuffer): List<ByteArray>? {
        if (buf.remaining() < 1) return null
        val count = buf.get().toInt() and 0xff
        if (count == 0) return null
        val out = ArrayList<ByteArray>(count)
        repeat(count) {
            if (buf.remaining() < 2) return null
            val len = buf.getShort().toInt() and 0xffff
            if (len == 0 || buf.remaining() < len) return null
            val f = ByteArray(len)
            buf.get(f)
            out += f
        }
        return out
    }

    fun encodeKeepalive(buf: ByteBuffer, sender: Int, seq: Int): Int {
        writeHeader(buf, TYPE_KEEPALIVE, sender, seq)
        return HEADER
    }

    fun encodeBye(buf: ByteBuffer, sender: Int, seq: Int): Int {
        writeHeader(buf, TYPE_BYE, sender, seq)
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
