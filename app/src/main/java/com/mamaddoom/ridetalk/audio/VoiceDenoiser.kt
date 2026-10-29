package com.mamaddoom.ridetalk.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single-channel speech enhancement for wind, engine and road noise.
 *
 * - STFT: 256-point, 50 % overlap, sqrt-Hann analysis/synthesis (16 ms latency).
 * - Noise estimate: MCRA (minima-controlled recursive averaging, Cohen 2002).
 *   The spectral minimum is tracked over a short window and the noise is only
 *   updated where speech is unlikely, so it follows changing noise such as
 *   wind gusts (faster below 1 kHz) without eating sustained vowels.
 * - Gain: OM-LSA (optimally modified log-spectral amplitude): the
 *   Ephraim–Malah LSA gain weighted by the speech presence probability, with
 *   a floor [gMin] where speech is absent. This removes noise strongly while
 *   avoiding the "musical noise" of plain spectral subtraction.
 *
 * Also exposes per-frame speech features for the voice-activity detector:
 * [presence] (speech band 300–3400 Hz) and [snrDb].
 */
class VoiceDenoiser(
    private val rate: Int = 16000,
    /**
     * True (microphone): learn the noise floor from the first ~100 ms.
     * False (received voice): the stream starts with speech, so start from a
     * quiet floor and let minimum tracking find the real noise.
     */
    private val learnInitialNoise: Boolean = true,
) {
    /** 0 = bypass, 100 = strongest. Safe to change from another thread. */
    @Volatile var strength = 60

    /** Mean speech presence probability in the speech band, last frame (0..1). */
    @Volatile var presence = 0f
        private set
    /** Mean a-posteriori SNR in the speech band, last frame. */
    @Volatile var snrDb = 0f
        private set

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

    private val power = FloatArray(bins)
    private val smooth = FloatArray(bins)
    private val sMin = FloatArray(bins)
    private val sTmp = FloatArray(bins)
    private val prob = FloatArray(bins)
    private val noise = FloatArray(bins)
    private val prevGain = FloatArray(bins) { 1f }
    private val prevGamma = FloatArray(bins) { 1f }
    private val gain = FloatArray(bins)
    private var frames = 0
    private var minCount = 0

    init {
        if (!learnInitialNoise) startQuiet()
    }

    private fun startQuiet() {
        frames = INIT_FRAMES
        noise.fill(QUIET_FLOOR)
        sMin.fill(QUIET_FLOOR)
        sTmp.fill(Float.MAX_VALUE)
    }

    private val lowBins = (1000.0 * n / rate).toInt()
    private val speechLo = (300.0 * n / rate).toInt()
    private val speechHi = (3400.0 * n / rate).toInt()

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
        smooth.fill(0f); sMin.fill(0f); sTmp.fill(0f); prob.fill(0f); noise.fill(0f)
        prevGain.fill(1f); prevGamma.fill(1f)
        pos = 0; frames = 0; minCount = 0
        if (!learnInitialNoise) startQuiet()
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
        for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k] + 1e-10f

        frames++
        trackNoise()

        val s = strength.coerceIn(0, 100)
        val gMin = 10f.pow((-8f - s * 0.25f) / 20f) // -8 dB .. -33 dB where speech is absent
        val over = 1f + s / 200f
        var presSum = 0f
        var snrSum = 0f
        for (k in 0 until bins) {
            val lambda = noise[k] * over
            val gamma = min(power[k] / lambda, 1000f)
            // Decision-directed a-priori SNR.
            val xi = max(XI_MIN, DD * prevGain[k] * prevGain[k] * prevGamma[k] + (1 - DD) * max(gamma - 1f, 0f))
            val v = xi / (1f + xi) * gamma
            val gLsa = min(1f, xi / (1f + xi) * exp(0.5f * expInt(v)))
            val p = prob[k]
            gain[k] = if (s == 0) 1f else gLsa.pow(p) * gMin.pow(1f - p)
            prevGain[k] = gLsa
            prevGamma[k] = gamma
            if (k in speechLo..speechHi) {
                presSum += p
                snrSum += gamma
            }
        }
        val band = (speechHi - speechLo + 1).toFloat()
        presence = presSum / band
        snrDb = (10 * log10((snrSum / band).toDouble().coerceAtLeast(1e-3))).toFloat()

        if (s != 0) {
            // Light smoothing across frequency against residual "musical noise".
            var prev = gain[0]
            for (k in 1 until bins - 1) {
                val cur = gain[k]
                gain[k] = 0.25f * prev + 0.5f * cur + 0.25f * gain[k + 1]
                prev = cur
            }
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

    /** MCRA: minimum tracking, speech presence probability, presence-gated noise update. */
    private fun trackNoise() {
        for (k in 0 until bins) {
            // Smooth over neighbouring bins, then over time.
            val lo = power[max(0, k - 1)]
            val hi = power[min(bins - 1, k + 1)]
            val f = 0.25f * lo + 0.5f * power[k] + 0.25f * hi
            smooth[k] = if (frames == 1) f else ALPHA_S * smooth[k] + (1 - ALPHA_S) * f
        }
        if (frames <= INIT_FRAMES) {
            // Learn the initial noise floor from the first ~100 ms.
            for (k in 0 until bins) {
                noise[k] = (noise[k] * (frames - 1) + smooth[k]) / frames
                sMin[k] = if (frames == 1) smooth[k] else min(sMin[k], smooth[k])
                sTmp[k] = sMin[k]
            }
            return
        }
        val restart = ++minCount >= MIN_WINDOW
        for (k in 0 until bins) {
            val sk = smooth[k]
            if (restart) {
                sMin[k] = min(sTmp[k], sk)
                sTmp[k] = sk
            } else {
                sMin[k] = min(sMin[k], sk)
                sTmp[k] = min(sTmp[k], sk)
            }
            val speech = if (sk > DELTA * sMin[k]) 1f else 0f
            prob[k] = ALPHA_P * prob[k] + (1 - ALPHA_P) * speech
            // Wind and engine noise live low and change fast: track them faster there.
            val alphaD = if (k <= lowBins) 0.80f else 0.92f
            val a = alphaD + (1 - alphaD) * prob[k]
            noise[k] = a * noise[k] + (1 - a) * power[k]
        }
        if (restart) minCount = 0
    }

    private companion object {
        const val FFT_SIZE = 256
        const val INIT_FRAMES = 12
        /** Minimum-search window: 100 frames of 8 ms = 0.8 s. */
        const val MIN_WINDOW = 100
        const val ALPHA_S = 0.8f
        const val ALPHA_P = 0.2f
        const val DELTA = 7f
        const val DD = 0.92f
        const val XI_MIN = 0.003f // -25 dB
        /** Per-bin power of a near-silent floor (about -90 dBFS). */
        const val QUIET_FLOOR = 1e-7f

        /** Exponential integral E1(x), approximations good to ~1e-4 (Abramowitz–Stegun 5.1.53/5.1.56). */
        fun expInt(x: Float): Float {
            if (x <= 0f) return 20f
            if (x < 1f) {
                return (-0.57721566f - ln(x) + 0.99999193f * x - 0.24991055f * x * x +
                    0.05519968f * x * x * x - 0.00976004f * x * x * x * x + 0.00107857f * x * x * x * x * x)
            }
            if (x > 30f) return 0f
            val num = x * x + 2.334733f * x + 0.250621f
            val den = x * x + 3.330657f * x + 1.681534f
            return exp(-x) / x * (num / den)
        }
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
