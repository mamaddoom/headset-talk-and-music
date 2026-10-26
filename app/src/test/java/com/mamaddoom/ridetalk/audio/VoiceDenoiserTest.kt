package com.mamaddoom.ridetalk.audio

import com.mamaddoom.ridetalk.audio.Synth.RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class VoiceDenoiserTest {
    private val latency = 256

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    /** Runs the full capture chain (wind guard + denoiser) in 20 ms frames; returns the output. */
    private fun chain(input: FloatArray, strength: Int, vad: SpeechDetector? = null, speechFrames: MutableList<Boolean>? = null): FloatArray {
        val wind = WindGuard(RATE)
        val ns = VoiceDenoiser(RATE).apply { this.strength = strength }
        val out = input.copyOf()
        val frame = FloatArray(320)
        var i = 0
        while (i + 320 <= out.size) {
            System.arraycopy(out, i, frame, 0, 320)
            wind.process(frame, 320, strength)
            ns.process(frame, 320)
            if (vad != null) speechFrames?.add(vad.update(ns.presence, ns.snrDb, Pcm.rmsDb(frame, 320), frame, 320))
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
        val ns = VoiceDenoiser(RATE).apply { strength = 0 }
        val input = FloatArray(RATE) { sin(2 * PI * 440 * it / RATE).toFloat() * 0.5f }
        val out = input.copyOf()
        val frame = FloatArray(320)
        var i = 0
        while (i + 320 <= out.size) {
            System.arraycopy(out, i, frame, 0, 320); ns.process(frame, 320); System.arraycopy(frame, 0, out, i, 320); i += 320
        }
        for (k in 1000 until RATE - latency) assertEquals(input[k], out[k + latency], 1e-4f)
    }

    @Test
    fun removesNoiseAndKeepsSpeech() {
        val n = RATE * 6
        val voiced = BooleanArray(n)
        val speech = Synth.speech(n, seed = 3, voiced = voiced)
        for (i in 0 until RATE * 2) { speech[i] = 0f; voiced[i] = false } // first 2 s: noise only
        val noise = Synth.white(n, seed = 9, rms = 0.03f)
        val input = FloatArray(n) { speech[it] + noise[it] }

        for (strength in intArrayOf(40, 70, 100)) {
            val out = chain(input, strength)
            val reduction = db(Synth.rms(input, 8000, 30000), Synth.rms(out, 8000 + latency, 30000 + latency))
            // Speech kept: energy inside syllables vs. the clean syllables.
            var se = 0.0; var oe = 0.0
            for (i in RATE * 2 until n - latency) if (voiced[i]) {
                se += speech[i].toDouble() * speech[i]; oe += out[i + latency].toDouble() * out[i + latency]
            }
            val kept = 10 * log10(oe / se)
            println("white noise, strength=$strength: noise -${"%.1f".format(reduction)} dB, speech ${"%.1f".format(kept)} dB")
            assertTrue("strength $strength: noise only reduced $reduction dB", reduction > 8 + strength / 10.0)
            // A few dB of level loss is fine: the speech AGC that follows restores it.
            assertTrue("strength $strength: speech changed by $kept dB", kept > -5.0 && kept < 1.5)
        }
    }

    @Test
    fun windIsStronglyReduced() {
        val n = RATE * 6
        val input = Synth.wind(n, seed = 5, rms = 0.12f)
        for (strength in intArrayOf(40, 70, 100)) {
            val out = chain(input, strength)
            val reduction = db(Synth.rms(input, RATE, n - latency), Synth.rms(out, RATE + latency, n))
            println("wind only, strength=$strength: -${"%.1f".format(reduction)} dB")
            assertTrue("strength $strength: wind only reduced $reduction dB", reduction > 15 + strength / 10.0)
        }
    }

    @Test
    fun speechInWindStaysIntelligible() {
        val n = RATE * 6
        val voiced = BooleanArray(n)
        val speech = Synth.speech(n, seed = 11, voiced = voiced)
        val wind = Synth.wind(n, seed = 2, rms = 0.10f)
        val out = chain(FloatArray(n) { speech[it] + wind[it] }, strength = 70)
        // Compare in the speech band only (wind guard removes < 300 Hz by design).
        val hp = { x: FloatArray, off: Int -> val f = Biquad.highPass(RATE, 300.0); FloatArray(x.size - off) { f.process(x[it + off]) } }
        val s = hp(speech, 0); val o = hp(out, latency)
        var se = 0.0; var oe = 0.0; var ne = 0.0; var nWin = 0
        for (i in RATE until o.size) {
            if (voiced[i]) { se += s[i].toDouble() * s[i]; oe += o[i].toDouble() * o[i] } else { ne += o[i].toDouble() * o[i]; nWin++ }
        }
        val kept = 10 * log10(oe / se)
        val residual = 10 * log10((ne / nWin) / (oe / voiced.count { it }))
        println("speech in wind: speech band kept ${"%.1f".format(kept)} dB, pauses ${"%.1f".format(residual)} dB below speech")
        assertTrue("speech lost $kept dB in wind", kept > -6.0)
    }
}
