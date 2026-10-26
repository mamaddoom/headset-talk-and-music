package com.mamaddoom.ridetalk.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wind guard: detects wind by its signature (energy piled up below ~250 Hz
 * with a high overall level) and raises a high-pass cut-off while it blows,
 * then relaxes it again. Speech intelligibility lives above 300 Hz, wind
 * rumble mostly below, so this removes the bulk of wind before the spectral
 * denoiser even sees it, and keeps gusts from overloading it.
 */
class WindGuard(private val rate: Int) {
    private val hpf = Biquad.highPass(rate, BASE_HZ)
    private val probeLow = Biquad.lowPass(rate, 250.0)
    private var cutoff = BASE_HZ
    private var smoothed = 0f

    /** 0..1 how windy the last frame was, for the UI. */
    @Volatile var windiness = 0f
        private set

    /** Filters [n] samples of [x] in place. [strength] 0..100 scales how far the cut-off may rise. */
    fun process(x: FloatArray, n: Int, strength: Int) {
        var low = 0.0
        var total = 0.0
        for (i in 0 until n) {
            val v = x[i]
            val l = probeLow.process(v)
            low += (l * l).toDouble()
            total += (v * v).toDouble()
        }
        val levelDb = if (total <= 0) -120.0 else 10 * log10(total / n)
        val ratio = if (total <= 1e-12) 0.0 else low / total
        // Windy = most energy below 250 Hz while reasonably loud.
        val raw = ((ratio - 0.45) / 0.4).coerceIn(0.0, 1.0) * ((levelDb + 50) / 20).coerceIn(0.0, 1.0)
        smoothed += ((raw - smoothed) * (if (raw > smoothed) 0.5 else 0.08)).toFloat()
        windiness = smoothed

        val s = strength.coerceIn(0, 100)
        val base = BASE_HZ + s * 0.6 // 80 .. 140 Hz
        val target = min(MAX_HZ, base + smoothed * (120 + s * 2.3)) // up to ~450 Hz in strong wind
        // Glide so the response never jumps audibly.
        cutoff += (target - cutoff) * 0.3
        hpf.retuneHighPass(rate, cutoff)
        for (i in 0 until n) x[i] = hpf.process(x[i])
    }

    private companion object {
        const val BASE_HZ = 80.0
        const val MAX_HZ = 450.0
    }
}

/**
 * Voice-activity detector that listens for *speech*, not just loudness:
 * combines the denoiser's speech presence probability, the speech-band SNR
 * and voicing (pitch periodicity). Wind, engine and road noise are loud but
 * score low on all three, so they no longer hold the mic open.
 */
class SpeechDetector(private val rate: Int) {
    /** 0..100, higher opens on softer speech. */
    @Volatile var sensitivity = 55

    var score = 0f
        private set

    private var hang = 0
    private var above = 0
    // Voicing is measured above 700 Hz: speech harmonics keep the pitch period
    // there, while wind left over after the wind guard is narrowband and low.
    private val voicingHp1 = Biquad.highPass(rate, 700.0)
    private val voicingHp2 = Biquad.highPass(rate, 700.0)
    private var hpBuf = FloatArray(0)

    /** Voicing (0..1) of the last frame, for tests/diagnostics. */
    var lastVoicing = 0f
        private set

    /** Returns true if this frame should be treated as speech (includes hangover). */
    fun update(presence: Float, snrDb: Float, levelDb: Float, frame: FloatArray, n: Int): Boolean {
        if (hpBuf.size < n) hpBuf = FloatArray(n)
        for (i in 0 until n) hpBuf[i] = voicingHp2.process(voicingHp1.process(frame[i]))
        val v = voicing(hpBuf, n)
        lastVoicing = v
        val snrTerm = ((snrDb - 2f) / 10f).coerceIn(0f, 1f)
        val pitch = ((v - 0.3f) / 0.4f).coerceIn(0f, 1f)
        score = 0.4f * presence + 0.25f * snrTerm + 0.35f * pitch
        val threshold = 0.62f - sensitivity.coerceIn(0, 100) * 0.004f // 0.62 .. 0.22
        // Opening needs a voiced frame; consonants around it ride on pre-roll and hangover.
        val speechNow = levelDb > -58f && score > threshold && v > VOICED
        above = if (speechNow) above + 1 else 0
        // Three voiced frames in a row (60 ms) open the gate: a syllable is longer, a gust's blip isn't.
        if (above >= CONFIRM) hang = HANGOVER
        else if (hang > 0) hang--
        return hang > 0
    }

    /** Keeps the gate open for at least [frames] more frames (tap-to-talk). */
    fun hold(frames: Int) {
        hang = max(hang, frames)
    }

    fun reset() {
        hang = 0
        above = 0
    }

    /** Normalised autocorrelation peak for pitch lags 80–400 Hz: ~0.8 for vowels, low for noise. */
    private fun voicing(x: FloatArray, n: Int): Float {
        val minLag = rate / 400
        val maxLag = min(rate / 80, n / 2)
        var e0 = 0.0
        for (i in 0 until n) e0 += (x[i] * x[i]).toDouble()
        if (e0 < 1e-9) return 0f
        var best = 0.0
        var lag = minLag
        while (lag <= maxLag) {
            var c = 0.0
            var e1 = 0.0
            var e2 = 0.0
            for (i in 0 until n - lag) {
                c += (x[i] * x[i + lag]).toDouble()
                e1 += (x[i] * x[i]).toDouble()
                e2 += (x[i + lag] * x[i + lag]).toDouble()
            }
            val r = c / sqrt(e1 * e2 + 1e-12)
            if (r > best) best = r
            lag += 2 // half resolution is plenty for a yes/no voicing cue
        }
        return best.toFloat()
    }

    private companion object {
        const val HANGOVER = 30 // 600 ms
        const val VOICED = 0.5f
        const val CONFIRM = 3
    }
}

/**
 * Automatic gain control that only learns from speech, so it never pumps
 * noise up in pauses. Fast to turn a shout down, slow to bring a whisper up.
 */
class SpeechAgc(
    private val targetDb: Float,
    private val maxGainDb: Float,
    private val minGainDb: Float = -10f,
) {
    var gainDb = 0f
        private set

    /** Updates from one frame at [levelDb] and returns the linear gain to apply. */
    fun update(levelDb: Float, speech: Boolean): Float {
        if (speech && levelDb > -55f) {
            val desired = (targetDb - levelDb).coerceIn(minGainDb, maxGainDb)
            gainDb += (desired - gainDb) * (if (desired < gainDb) 0.15f else 0.03f)
        }
        return 10f.pow(gainDb / 20f)
    }

    fun reset() {
        gainDb = 0f
    }
}
