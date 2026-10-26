package com.mamaddoom.ridetalk.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.mamaddoom.ridetalk.net.Protocol

/**
 * Reads the headset microphone in 20 ms frames, cleans it up and decides
 * whether to transmit (voice activity or push-to-talk).
 */
class VoiceCapture(
    private val preferredInput: AudioDeviceInfo?,
    private val sink: Sink,
) {
    interface Sink {
        /** Called on the capture thread for every frame that should be sent. */
        fun onVoiceFrame(pcm: ShortArray)
        /** Called on the capture thread ~10x per second. */
        fun onMicLevel(levelDb: Float, transmitting: Boolean)
        /** True while another rider's voice is playing here (for anti-feedback). */
        fun isFarEndActive(): Boolean
    }

    enum class Mode { VOX, PTT }

    @Volatile var mode = Mode.VOX
    @Volatile var muted = false
    @Volatile var pttPressed = false
    @Volatile var gain = 1f
    /** Speech detector sensitivity 0..100 (higher opens on softer speech). */
    @Volatile var vadSensitivity = 55
    /** 0..1 how windy it is right now (for the UI). */
    @Volatile var windiness = 0f
        private set
    /** Noise reduction 0..100: drives the spectral denoiser and the wind high-pass. */
    @Volatile var noiseReduction = 60
    /**
     * Anti-feedback: while someone else is talking, our mic is held 20 dB down
     * unless we clearly talk ourselves, so a speaker-to-mic loop between two
     * phones can't build up into howling or echo.
     */
    @Volatile var antiFeedback = true
    /** Set >0 to force the VOX gate open for that many frames (tap-to-talk). */
    @Volatile var holdOpenFrames = 0

    @Volatile private var running = false
    private var thread: Thread? = null
    private val effects = ArrayList<AudioEffect>()

    /** False if the microphone could not be opened (permission, or busy in a call). */
    @Volatile var ok = true
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "rt-capture").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission") // checked by the activity before the service starts
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = Protocol.VOICE_FRAME
        val minBuf = AudioRecord.getMinBufferSize(
            Protocol.VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Protocol.VOICE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame * 2 * 4),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            ok = false
            running = false
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            ok = false
            running = false
            return
        }
        preferredInput?.let { record.preferredDevice = it }
        enableEffects(record.audioSessionId)

        val raw = ShortArray(frame)
        val f = FloatArray(frame)
        // Processing chain: wind guard -> spectral denoiser -> speech detector -> AGC -> limiter.
        val wind = WindGuard(Protocol.VOICE_RATE)
        val denoiser = VoiceDenoiser(Protocol.VOICE_RATE)
        val vad = SpeechDetector(Protocol.VOICE_RATE)
        val agc = SpeechAgc(targetDb = -20f, maxGainDb = 15f)
        var expander = 1f
        // Pre-roll so the detector's confirmation delay doesn't chop the first syllable.
        val preRoll = Array(PRE_ROLL) { ShortArray(frame) }
        var preRollCount = 0
        var wasSending = false
        var meterTick = 0

        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < frame && running) {
                    val n = record.read(raw, got, frame - got)
                    if (n < 0) throw IllegalStateException("read error $n")
                    got += n
                }
                if (!running) break

                val nr = noiseReduction
                denoiser.strength = nr
                vad.sensitivity = vadSensitivity
                for (i in 0 until frame) f[i] = raw[i] / 32768f
                wind.process(f, frame, nr)
                denoiser.process(f, frame)
                val cleanDb = Pcm.rmsDb(f, frame)

                val hold = holdOpenFrames
                if (hold > 0) {
                    vad.hold(hold)
                    holdOpenFrames = 0
                }
                var speech = vad.update(denoiser.presence, denoiser.snrDb, cleanDb, f, frame)
                // Anti-feedback (loudspeaker only): while someone else talks, only clear local speech passes.
                if (antiFeedback && sink.isFarEndActive() && vad.score < FEEDBACK_SCORE) speech = false

                // Level: AGC learns from speech only; between words the expander pulls residual noise down.
                val g = agc.update(cleanDb, speech) * gain
                expander += ((if (speech) 1f else EXPANDER_FLOOR) - expander) * (if (speech) 0.5f else 0.1f)
                val out = ShortArray(frame)
                val total = g * expander
                for (i in 0 until frame) out[i] = Pcm.toShort(f[i] * total)
                val level = cleanDb + 20f * kotlin.math.log10(total.coerceAtLeast(1e-4f))

                val send = !muted && when (mode) {
                    Mode.PTT -> pttPressed
                    Mode.VOX -> speech
                }
                if (send) {
                    if (!wasSending && mode == Mode.VOX) {
                        for (k in preRollCount downTo 1) sink.onVoiceFrame(preRoll[PRE_ROLL - k].copyOf())
                    }
                    sink.onVoiceFrame(out)
                    preRollCount = 0
                } else {
                    for (k in 0 until PRE_ROLL - 1) System.arraycopy(preRoll[k + 1], 0, preRoll[k], 0, frame)
                    System.arraycopy(out, 0, preRoll[PRE_ROLL - 1], 0, frame)
                    if (preRollCount < PRE_ROLL) preRollCount++
                    if (mode == Mode.PTT || muted) vad.reset()
                }
                wasSending = send
                if (++meterTick >= 5) {
                    meterTick = 0
                    windiness = wind.windiness
                    sink.onMicLevel(level, send)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture stopped", e)
            ok = false
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            releaseEffects()
            record.release()
        }
    }

    private fun enableEffects(session: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { effects.add(it) }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { effects.add(it) }
            // No platform AGC: it pumps wind noise up in pauses. Our speech-only AGC replaces it.
            for (e in effects) e.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "audio effects unavailable", e)
        }
    }

    private fun releaseEffects() {
        for (e in effects) {
            try {
                e.release()
            } catch (_: Exception) {
            }
        }
        effects.clear()
    }

    private companion object {
        const val TAG = "VoiceCapture"
        const val PRE_ROLL = 4 // 80 ms, covers the detector's 60 ms confirmation
        const val EXPANDER_FLOOR = 0.25f // -12 dB between words (push-to-talk / open channel)
        const val FEEDBACK_SCORE = 0.7f
    }
}
