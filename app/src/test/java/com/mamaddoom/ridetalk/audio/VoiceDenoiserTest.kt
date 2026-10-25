package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VoiceDenoiserTest {
    private val rate = 16000
    private val latency = 256

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    /** Runs [input] through the suppressor in 320-sample capture frames. */
    private fun run(ns: VoiceDenoiser, input: FloatArray): FloatArray {
        val out = input.copyOf()
        val frame = FloatArray(320)
        var i = 0
        while (i + 320 <= out.size) {
            System.arraycopy(out, i, frame, 0, 320)
            ns.process(frame, 320)
            System.arraycopy(frame, 0, out, i, 320)
            i += 320
        }
        return out
    }

    @Test
    fun fftRoundTrip() {
        val f = Fft(256)
        val re = FloatArray(256) { Random(it).nextFloat() - 0.5f }
        val orig = re.copyOf()
        val im = FloatArray(256)
        f.transform(re, im, inverse = false)
        f.transform(re, im, inverse = true)
        for (i in 0 until 256) assertEquals(orig[i], re[i], 1e-5f)
    }

    @Test
    fun bypassReconstructsSignalPerfectly() {
        val ns = VoiceDenoiser().apply { strength = 0 }
        val input = FloatArray(rate) { sin(2 * PI * 440 * it / rate).toFloat() * 0.5f }
        val out = run(ns, input)
        for (i in 1000 until rate - latency) assertEquals(input[i], out[i + latency], 1e-4f)
    }

    @Test
    fun removesSteadyNoiseAndKeepsSpeechBand() {
        val rnd = Random(42)
        val n = rate * 3
        val noise = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.12f }
        val tone = FloatArray(n) { if (it >= rate * 3 / 2) (sin(2 * PI * 700 * it / rate) * 0.3).toFloat() else 0f }
        val input = FloatArray(n) { noise[it] + tone[it] }

        for (strength in intArrayOf(40, 70, 100)) {
            val out = run(VoiceDenoiser().apply { this.strength = strength }, input)
            // Noise-only stretch (after the floor has been learned).
            val reduction = db(rms(input, 16000, 22000), rms(out, 16000 + latency, 22000 + latency))
            // Tone + noise stretch: the tone must survive.
            val toneKept = db(rms(out, 34000 + latency, 46000 + latency), rms(tone, 34000, 46000))
            println("strength=$strength noise -${"%.1f".format(reduction)} dB, tone ${"%.1f".format(toneKept)} dB")
            assertTrue("strength $strength: noise only reduced $reduction dB", reduction > 6 + strength / 10.0)
            assertTrue("strength $strength: tone changed by $toneKept dB", toneKept > -3.0 && toneKept < 2.0)
        }
    }
}
