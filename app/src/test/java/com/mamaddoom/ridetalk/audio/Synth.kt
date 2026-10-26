package com.mamaddoom.ridetalk.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic test signals: speech-like syllables, wind, white noise. */
object Synth {
    const val RATE = 16000

    /**
     * Speech-like signal: voiced syllables (gliding 110–190 Hz pitch,
     * harmonics up to 3.5 kHz shaped by two formant bumps) separated by short
     * pauses. [voiced] is set true for samples inside a syllable.
     */
    fun speech(n: Int, seed: Int, voiced: BooleanArray? = null, peak: Float = 0.3f): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(n)
        var i = 0
        var phase = 0.0
        while (i < n) {
            val len = (0.12 + rnd.nextDouble() * 0.14) * RATE
            val gap = (0.06 + rnd.nextDouble() * 0.12) * RATE
            val f0a = 110 + rnd.nextDouble() * 80
            val f0b = 110 + rnd.nextDouble() * 80
            val f1 = 500 + rnd.nextDouble() * 400
            val f2 = 1200 + rnd.nextDouble() * 900
            for (j in 0 until len.toInt()) {
                if (i >= n) break
                val t = j / len
                val f0 = f0a + (f0b - f0a) * t
                phase += 2 * PI * f0 / RATE
                var v = 0.0
                var k = 1
                while (k * f0 < 3500) {
                    val f = k * f0
                    val w = 1.0 / k + 1.5 * bump(f, f1, 150.0) + 1.0 * bump(f, f2, 250.0)
                    v += w * sin(k * phase)
                    k++
                }
                val env = minOf(1.0, j / (0.015 * RATE), (len - j) / (0.015 * RATE))
                out[i] = (v * env).toFloat()
                voiced?.set(i, env > 0.5)
                i++
            }
            i += gap.toInt()
        }
        var m = 0f
        for (v in out) m = maxOf(m, abs(v))
        if (m > 0) for (j in out.indices) out[j] *= peak / m
        return out
    }

    private fun bump(f: Double, centre: Double, width: Double): Double {
        val d = (f - centre) / width
        return 1.0 / (1.0 + d * d)
    }

    /** Wind: strong low-frequency rumble with random gusts, plus a little broadband hiss. */
    fun wind(n: Int, seed: Int, rms: Float = 0.12f): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(n)
        var l1 = 0.0
        var l2 = 0.0
        var hiss = 0.0
        var gust = 0.6
        var gustTarget = 0.6
        val a = 2 * PI * 150 / RATE
        for (i in 0 until n) {
            if (i % (RATE / 5) == 0) gustTarget = 0.2 + rnd.nextDouble() * 1.0
            gust += (gustTarget - gust) * 0.0005
            val w = rnd.nextDouble() * 2 - 1
            l1 += a * (w - l1)
            l2 += a * (l1 - l2)
            hiss += 0.3 * (w - hiss)
            out[i] = (gust * (l2 * 8 + hiss * 0.08)).toFloat()
        }
        scaleRms(out, rms)
        return out
    }

    fun white(n: Int, seed: Int, rms: Float): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        scaleRms(out, rms)
        return out
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun scaleRms(x: FloatArray, target: Float) {
        val r = rms(x)
        if (r > 0) for (i in x.indices) x[i] = (x[i] * target / r).toFloat()
    }
}
