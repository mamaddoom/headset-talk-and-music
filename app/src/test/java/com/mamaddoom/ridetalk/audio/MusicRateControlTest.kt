package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRateControlTest {

    @Test
    fun stepsDownFastAndUpSlowly() {
        val c = MusicRateControl()
        assertEquals(128_000, c.current.bitrate)
        assertTrue(c.update(12, 0))
        assertEquals(96_000, c.current.bitrate)
        // Held: no second step within 2 s.
        assertFalse(c.update(12, 1000))
        assertTrue(c.update(12, 2000))
        assertEquals(64_000, c.current.bitrate)
        // Clean link: back up one level per 10 s.
        assertFalse(c.update(0, 3000))
        assertFalse(c.update(0, 12_000))
        assertTrue(c.update(0, 13_000))
        assertEquals(96_000, c.current.bitrate)
    }

    @Test
    fun heavyLossJumpsTwoLevels() {
        val c = MusicRateControl()
        c.update(40, 0)
        assertEquals(64_000, c.current.bitrate)
        assertEquals(3, c.current.redundancy)
        c.update(40, 5000)
        c.update(40, 10_000)
        assertEquals(40_000, c.current.bitrate) // floor
    }

    @Test
    fun noReportsKeepsLevel() {
        val c = MusicRateControl()
        c.update(10, 0)
        repeat(30) { c.update(null, 1000L + it * 1000) }
        assertEquals(96_000, c.current.bitrate)
    }

    @Test
    fun lowerBitrateStillDecodes() {
        val enc = MusicEncoder()
        val dec = MusicDecoder()
        val pcm = ShortArray(com.mamaddoom.ridetalk.net.Protocol.MUSIC_SAMPLES) {
            (Math.sin(it / 2 * 2 * Math.PI * 440 / 48000.0) * 8000).toInt().toShort()
        }
        val out = ShortArray(pcm.size)
        var big = 0
        repeat(10) { big += enc.encode(pcm)!!.size }
        enc.bitrate = 40_000
        var small = 0
        repeat(10) { small += enc.encode(pcm)!!.size }
        assertTrue("smaller packets at 40k: $small vs $big", small < big)
        assertTrue(dec.decode(enc.encode(pcm)!!, out))
    }
}
