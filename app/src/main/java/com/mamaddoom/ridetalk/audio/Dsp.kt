package com.mamaddoom.ridetalk.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR filter (RBJ cookbook), transposed direct form II. */
class Biquad private constructor(
    private var b0: Float, private var b1: Float, private var b2: Float,
    private var a1: Float, private var a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    /**
     * Re-tunes this filter as a high-pass at [freq] while keeping its state, so
     * an adaptive cut-off (wind guard) can move frame by frame without clicks.
     */
    fun retuneHighPass(rate: Int, freq: Double, q: Double = Q) {
        val f = highPass(rate, freq, q)
        b0 = f.b0; b1 = f.b1; b2 = f.b2; a1 = f.a1; a2 = f.a2
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f; z2 = 0f
    }

    companion object {
        private const val Q = 0.7071

        fun highPass(rate: Int, freq: Double, q: Double = Q): Biquad {
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 + c) / 2 / a0).toFloat(), (-(1 + c) / a0).toFloat(), ((1 + c) / 2 / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
            )
        }

        /** Peaking EQ: boosts (or cuts) [gainDb] around [freq]. */
        fun peaking(rate: Int, freq: Double, gainDb: Double, q: Double): Biquad {
            val a = Math.pow(10.0, gainDb / 40)
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha / a
            return Biquad(
                ((1 + alpha * a) / a0).toFloat(), (-2 * c / a0).toFloat(), ((1 - alpha * a) / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha / a) / a0).toFloat(),
            )
        }

        fun lowPass(rate: Int, freq: Double, q: Double = Q): Biquad {
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                ((1 - c) / 2 / a0).toFloat(), ((1 - c) / a0).toFloat(), ((1 - c) / 2 / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
            )
        }
    }
}

/** 4th-order Butterworth low-pass (two cascaded biquads), used as anti-alias filter. */
class LowPass4(rate: Int, freq: Double) {
    private val a = Biquad.lowPass(rate, freq, 0.5412)
    private val b = Biquad.lowPass(rate, freq, 1.3066)

    fun process(x: Float) = b.process(a.process(x))

    fun reset() {
        a.reset(); b.reset()
    }
}

/**
 * Gentle feed-forward compressor with make-up gain. Used on music squeezed
 * into a Bluetooth call link: quiet passages come up so the song stays
 * intelligible over wind and engine noise, loud peaks stay below clipping.
 */
class Compressor(
    rate: Int,
    private val thresholdDb: Float = -24f,
    private val ratio: Float = 3f,
    private val makeupDb: Float = 5f,
) {
    private val attack = 1f - Math.exp(-1.0 / (0.005 * rate)).toFloat()
    private val release = 1f - Math.exp(-1.0 / (0.150 * rate)).toFloat()
    private var env = 0f

    fun process(x: Float): Float {
        val a = kotlin.math.abs(x)
        env += (a - env) * (if (a > env) attack else release)
        val levelDb = if (env < 1e-6f) -120f else (20 * log10(env.toDouble())).toFloat()
        val over = levelDb - thresholdDb
        val gainDb = (if (over > 0) -over * (1 - 1 / ratio) else 0f) + makeupDb
        return x * Math.pow(10.0, gainDb / 20.0).toFloat()
    }
}

/**
 * Streaming linear-interpolation resampler for interleaved float audio.
 * Keeps the last input frame between calls so chunk boundaries are seamless.
 */
class StreamResampler(inRate: Int, outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    // Position in the extended signal e[0] = prev, e[k] = input[k - 1].
    private var pos = 0.0
    private val prev = FloatArray(channels)
    private var hasPrev = false

    var output = FloatArray(0)
        private set

    /** Resamples [frames] frames of [input]; returns the frame count written to [output]. */
    fun process(input: FloatArray, frames: Int): Int {
        if (frames <= 0) return 0
        if (!hasPrev) {
            for (c in 0 until channels) prev[c] = input[c]
            hasPrev = true
            pos = 1.0
        }
        val maxOut = (frames / step).toInt() + 2
        if (output.size < maxOut * channels) output = FloatArray(maxOut * channels)
        var n = 0
        while (true) {
            val i = pos.toInt()
            if (i > frames - 1) break
            val frac = (pos - i).toFloat()
            for (c in 0 until channels) {
                val a = if (i == 0) prev[c] else input[(i - 1) * channels + c]
                val b = input[i * channels + c]
                output[n * channels + c] = a + (b - a) * frac
            }
            n++
            pos += step
        }
        for (c in 0 until channels) prev[c] = input[(frames - 1) * channels + c]
        pos -= frames
        return n
    }

    fun reset() {
        hasPrev = false
        pos = 0.0
    }
}

object Pcm {
    /**
     * Whole-buffer linear resampling, for short clips (announcements). When
     * going down in rate a simple moving average first keeps the worst
     * aliasing out; speech clips are 16–24 kHz so that is rarely needed.
     */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        var src = x
        if (to < from) {
            val w = (from + to - 1) / to
            src = FloatArray(x.size) { i ->
                var acc = 0f
                var n = 0
                for (k in maxOf(0, i - w / 2)..minOf(x.size - 1, i + w / 2)) {
                    acc += x[k]
                    n++
                }
                acc / n
            }
        }
        val n = (src.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val f = (pos - j).toFloat()
            val a = src[minOf(j, src.size - 1)]
            val b = src[minOf(j + 1, src.size - 1)]
            a + (b - a) * f
        }
    }

    fun rmsDb(x: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += (x[i] * x[i]).toDouble()
        val rms = sqrt(sum / n)
        return if (rms < 1e-6) -120f else (20 * log10(rms)).toFloat()
    }

    fun rmsDb(x: ShortArray): Float {
        var sum = 0.0
        for (s in x) sum += s.toDouble() * s
        val rms = sqrt(sum / x.size) / 32768.0
        return if (rms < 1e-6) -120f else (20 * log10(rms)).toFloat()
    }

    /** Plain conversion with clamping; used for music so mastered peaks stay undistorted. */
    fun clampToShort(x: Float): Short = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    /** Soft clipper: transparent below 0.9, smoothly limits peaks when voices and music add up. */
    fun toShort(x: Float): Short {
        val y = when {
            x > KNEE -> KNEE + (1 - KNEE) * softKnee((x - KNEE) / (1 - KNEE))
            x < -KNEE -> -KNEE - (1 - KNEE) * softKnee((-x - KNEE) / (1 - KNEE))
            else -> x
        }
        return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    private const val KNEE = 0.9f

    /** Maps 0..inf to 0..1 smoothly. */
    private fun softKnee(t: Float) = t / (1f + t)
}
