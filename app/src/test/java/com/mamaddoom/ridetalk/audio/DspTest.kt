package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    @Test
    fun resamplerProducesExpectedLengthAcrossChunks() {
        val r = StreamResampler(44100, 32000, 2)
        var total = 0
        val chunk = FloatArray(1024 * 2) { 0.5f }
        repeat(100) { total += r.process(chunk, 1024) }
        val expected = 1024 * 100 * 32000.0 / 44100
        assertTrue("got $total expected ~$expected", abs(total - expected) <= 2)
    }

    @Test
    fun resamplerIsContinuousAtChunkBoundaries() {
        val inRate = 48000
        val r = StreamResampler(inRate, 32000, 1)
        val out = ArrayList<Float>()
        var t = 0
        repeat(50) {
            val n = 480 + (it % 7) * 13 // uneven chunks
            val start = t
            val buf = FloatArray(n) { i -> sin(2 * PI * 440 * (start + i) / inRate).toFloat() }
            t += n
            val m = r.process(buf, n)
            for (i in 0 until m) out += r.output[i]
        }
        // A 440 Hz sine at 32 kHz never jumps more than 2*pi*440/32000 ≈ 0.087 per sample.
        for (i in 1 until out.size) assertTrue("jump at $i", abs(out[i] - out[i - 1]) < 0.1f)
    }

    @Test
    fun highPassRemovesDc() {
        val hp = Biquad.highPass(16000, 120.0)
        var y = 0f
        repeat(16000) { y = hp.process(0.5f) }
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun voxHangoverKeepsGateOpen() {
        val g = VoxGate(hangoverFrames = 3)
        g.thresholdDb = -40f
        assertTrue(g.update(-20f))
        assertTrue(g.update(-80f))
        assertTrue(g.update(-80f))
        assertTrue(g.update(-80f))
        assertTrue(!g.update(-80f))
    }

    @Test
    fun softClipIsTransparentForNormalLevelsAndBounded() {
        assertEquals((0.5f * 32767).toInt().toShort(), Pcm.toShort(0.5f))
        assertTrue(Pcm.toShort(5f) <= Short.MAX_VALUE)
        assertTrue(Pcm.toShort(5f) > 30000)
        assertTrue(Pcm.toShort(-5f) < -30000)
    }
}
