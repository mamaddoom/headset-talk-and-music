package com.mamaddoom.ridetalk.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single-channel noise suppressor for wind, engine and road noise.
 *
 * STFT (256-point, 50 % overlap, sqrt-Hann) at 16 kHz; per-bin noise floor
 * tracked by a fast-fall/slow-rise follower of the smoothed power, and a
 * decision-directed Wiener gain. [strength] (0..100) scales over-subtraction
 * and how deep the residual noise may be pushed down (to -40 dB at 100).
 * Adds 16 ms of latency; costs one 256-point FFT pair per 8 ms.
 */
class VoiceDenoiser {
    /** 0 = bypass, 100 = strongest. Safe to change from another thread. */
    @Volatile var strength = 60

    private val n = FFT_SIZE
    private val hop = FFT_SIZE / 2
    private val bins = FFT_SIZE / 2 + 1
    private val window = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val fft = Fft(n)

    private val frame = FloatArray(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val newHalf = FloatArray(hop)
    private val prevHalf = FloatArray(hop)
    private val overlap = FloatArray(hop)
    private val ready = FloatArray(hop)
    private var pos = 0

    private val smoothed = FloatArray(bins)
    private val noise = FloatArray(bins)
    private val prevGain = FloatArray(bins) { 1f }
    private val prevSnrPost = FloatArray(bins) { 1f }
    private val gain = FloatArray(bins)
    private var frames = 0

    /** Processes [count] samples of [x] in place (output is delayed by 2 hops). */
    fun process(x: FloatArray, count: Int) {
        for (i in 0 until count) {
            newHalf[pos] = x[i]
            x[i] = ready[pos]
            if (++pos == hop) {
                pos = 0
                processFrame()
            }
        }
    }

    fun reset() {
        newHalf.fill(0f); prevHalf.fill(0f); overlap.fill(0f); ready.fill(0f)
        smoothed.fill(0f); noise.fill(0f); prevGain.fill(1f); prevSnrPost.fill(1f)
        pos = 0
        frames = 0
    }

    private fun processFrame() {
        System.arraycopy(prevHalf, 0, frame, 0, hop)
        System.arraycopy(newHalf, 0, frame, hop, hop)
        System.arraycopy(newHalf, 0, prevHalf, 0, hop)
        for (i in 0 until n) {
            re[i] = frame[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im, inverse = false)

        val s = strength.coerceIn(0, 100)
        val overSub = 1f + s / 40f // up to 3.5x
        val floor = 10f.pow((-6f - s * 0.34f) / 20f) // -6 dB .. -40 dB
        val sharp = 1f + s / 100f // gain exponent, makes strong settings bite harder
        frames++

        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12f
            smoothed[k] = if (frames == 1) p else 0.7f * smoothed[k] + 0.3f * p
            noise[k] = when {
                frames <= INIT_FRAMES -> (noise[k] * (frames - 1) + smoothed[k]) / frames
                smoothed[k] < noise[k] -> 0.85f * noise[k] + 0.15f * smoothed[k]
                else -> min(noise[k] * RISE, smoothed[k])
            }
            if (s == 0) {
                gain[k] = 1f
                continue
            }
            val snrPost = p / (noise[k] * overSub)
            val snrPrio = 0.96f * prevGain[k] * prevGain[k] * prevSnrPost[k] + 0.04f * max(snrPost - 1f, 0f)
            var g = snrPrio / (1f + snrPrio)
            if (sharp != 1f) g = g.pow(sharp)
            gain[k] = max(g, floor)
            prevGain[k] = gain[k]
            prevSnrPost[k] = min(snrPost, 1000f)
        }
        // Light smoothing across frequency against "musical noise" artefacts.
        var prev = gain[0]
        for (k in 1 until bins - 1) {
            val cur = gain[k]
            gain[k] = 0.25f * prev + 0.5f * cur + 0.25f * gain[k + 1]
            prev = cur
        }
        for (k in 0 until bins) {
            re[k] *= gain[k]; im[k] *= gain[k]
            if (k in 1 until n / 2) {
                re[n - k] = re[k]; im[n - k] = -im[k]
            }
        }
        fft.transform(re, im, inverse = true)

        for (i in 0 until hop) {
            ready[i] = overlap[i] + re[i] * window[i]
            overlap[i] = re[i + hop] * window[i + hop]
        }
    }

    private companion object {
        const val FFT_SIZE = 256
        const val INIT_FRAMES = 12 // ~100 ms to learn the initial noise floor
        /** Noise floor may rise ~3 dB/s, so speech is not mistaken for noise. */
        const val RISE = 1.006f
    }
}

/** In-place iterative radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) {
                re[i] *= s; im[i] *= s
            }
        }
    }
}
