package com.mamaddoom.ridetalk.audio

import com.mamaddoom.ridetalk.audio.Synth.RATE
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDetectorTest {
    /** Fraction of 20 ms frames the detector marks as speech (after a 1 s warm-up), and the voiced-frame mask. */
    private fun run(input: FloatArray, voiced: BooleanArray?, sensitivity: Int = 55): Pair<List<Boolean>, List<Boolean>> {
        val wind = WindGuard(RATE)
        val ns = VoiceDenoiser(RATE).apply { strength = 60 }
        val vad = SpeechDetector(RATE).apply { this.sensitivity = sensitivity }
        val frame = FloatArray(320)
        val decisions = ArrayList<Boolean>()
        val truth = ArrayList<Boolean>()
        var i = 0
        while (i + 320 <= input.size) {
            System.arraycopy(input, i, frame, 0, 320)
            wind.process(frame, 320, 60)
            ns.process(frame, 320)
            val d = vad.update(ns.presence, ns.snrDb, Pcm.rmsDb(frame, 320), frame, 320)
            if (i >= RATE) {
                decisions += d
                // Truth for the *processed* frame (delayed by the denoiser's 256 samples).
                truth += voiced != null && (i - 256 until i + 64).count { it >= 0 && voiced[it] } > 160
            }
            i += 320
        }
        return decisions to truth
    }

    @Test
    fun detectsSpeechInNoise() {
        val n = RATE * 8
        val voiced = BooleanArray(n)
        val speech = Synth.speech(n, seed = 4, voiced = voiced)
        val noise = Synth.white(n, seed = 1, rms = 0.02f)
        val (d, t) = run(FloatArray(n) { speech[it] + noise[it] }, voiced)
        val hits = d.indices.count { t[it] && d[it] }.toDouble() / t.count { it }
        println("speech detected in ${"%.0f".format(hits * 100)} % of voiced frames")
        assertTrue("missed speech: only $hits", hits > 0.85)
    }

    @Test
    fun windAloneDoesNotOpenTheMic() {
        val n = RATE * 10
        for (seed in 1..3) {
            val (d, _) = run(Synth.wind(n, seed = seed, rms = 0.15f), null)
            val fp = d.count { it }.toDouble() / d.size
            println("wind seed=$seed: mic open ${"%.1f".format(fp * 100)} % of the time")
            assertTrue("wind opened the mic ${fp * 100} % of the time", fp < 0.05)
        }
    }

    @Test
    fun steadyRoadNoiseDoesNotOpenTheMic() {
        val n = RATE * 10
        val (d, _) = run(Synth.white(n, seed = 7, rms = 0.08f), null)
        val fp = d.count { it }.toDouble() / d.size
        println("loud steady noise: mic open ${"%.1f".format(fp * 100)} % of the time")
        assertTrue("noise opened the mic ${fp * 100} % of the time", fp < 0.05)
    }

    @Test
    fun agcLevelsSpeechAndIgnoresNoise() {
        val agc = SpeechAgc(targetDb = -20f, maxGainDb = 15f)
        repeat(200) { agc.update(-35f, speech = true) } // a quiet talker
        assertTrue("quiet speech should be raised, gain ${agc.gainDb} dB", agc.gainDb > 12f)
        val before = agc.gainDb
        repeat(200) { agc.update(-30f, speech = false) } // wind between words
        assertTrue("noise must not move the gain", agc.gainDb == before)
        repeat(30) { agc.update(-5f, speech = true) } // a shout
        assertTrue("loud speech should be turned down quickly, gain ${agc.gainDb} dB", agc.gainDb < -5f)
    }
}
