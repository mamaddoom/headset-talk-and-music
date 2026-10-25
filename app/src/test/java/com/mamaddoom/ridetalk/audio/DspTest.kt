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

    private fun toneGain(filter: LowPass4, rate: Int, freq: Double): Float {
        var peak = 0f
        for (i in 0 until rate) {
            val y = filter.process(sin(2 * PI * freq * i / rate).toFloat())
            if (i > rate / 2) peak = maxOf(peak, abs(y))
        }
        return peak
    }

    @Test
    fun antiAliasFilterPassesMusicAndStopsAliases() {
        // 44.1 kHz source going to 32 kHz: keep 5 kHz, kill 20 kHz (would alias to 12 kHz).
        assertTrue(toneGain(LowPass4(44100, 14500.0), 44100, 5000.0) > 0.95f)
        assertTrue(toneGain(LowPass4(44100, 14500.0), 44100, 20000.0) < 0.3f)
        // 32 kHz -> 16 kHz in headset mode: 3 kHz voice band passes, 12 kHz is removed.
        assertTrue(toneGain(LowPass4(32000, 7000.0), 32000, 3000.0) > 0.95f)
        assertTrue(toneGain(LowPass4(32000, 7000.0), 32000, 12000.0) < 0.2f)
    }

    @Test
    fun musicConversionDoesNotCompressLoudPeaks() {
        // Mastered music peaks near full scale must come through unchanged.
        assertEquals((0.95f * 32767).toInt().toShort(), Pcm.clampToShort(0.95f))
        assertEquals(Short.MAX_VALUE, Pcm.clampToShort(1.5f))
        assertEquals(Short.MIN_VALUE, Pcm.clampToShort(-1.5f))
        // The mixer's soft clipper is transparent up to its knee.
        assertEquals((0.85f * 32767).toInt().toShort(), Pcm.toShort(0.85f))
    }
}
