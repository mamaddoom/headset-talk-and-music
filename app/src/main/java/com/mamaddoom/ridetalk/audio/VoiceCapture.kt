package com.mamaddoom.ridetalk.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
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
    @Volatile var voxThresholdDb = -40f
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
        val denoiser = VoiceDenoiser()
        var hpfStrength = -1
        var hpf = Biquad.highPass(Protocol.VOICE_RATE, 120.0)
        val gate = VoxGate(hangoverFrames = 40) // 800 ms
        // Two frames of pre-roll so VOX doesn't chop the first syllable.
        val preRoll = arrayOf(ShortArray(frame), ShortArray(frame))
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
                if (nr != hpfStrength) {
                    // Wind rumble and engine drone: 100 Hz cut-off, up to 220 Hz at full strength.
                    hpf = Biquad.highPass(Protocol.VOICE_RATE, 100.0 + nr * 1.2)
                    hpfStrength = nr
                    denoiser.strength = nr
                }
                val g = gain
                for (i in 0 until frame) f[i] = hpf.process(raw[i] / 32768f)
                denoiser.process(f, frame)
                for (i in 0 until frame) f[i] *= g
                var level = Pcm.rmsDb(f, frame)
                if (antiFeedback && mode == Mode.VOX && level < voxThresholdDb + 12f && sink.isFarEndActive()) {
                    for (i in 0 until frame) f[i] *= 0.1f
                    level -= 20f
                }
                val out = ShortArray(frame)
                for (i in 0 until frame) out[i] = Pcm.toShort(f[i])

                val hold = holdOpenFrames
                if (hold > 0) {
                    gate.hold(hold)
                    holdOpenFrames = 0
                }
                val send = !muted && when (mode) {
                    Mode.PTT -> pttPressed
                    Mode.VOX -> gate.also { it.thresholdDb = voxThresholdDb }.update(level)
                }
                if (send) {
                    if (!wasSending && mode == Mode.VOX) {
                        for (k in preRollCount downTo 1) sink.onVoiceFrame(preRoll[(2 - k)].copyOf())
                    }
                    sink.onVoiceFrame(out)
                    preRollCount = 0
                } else {
                    // Shift pre-roll window.
                    System.arraycopy(preRoll[1], 0, preRoll[0], 0, frame)
                    System.arraycopy(out, 0, preRoll[1], 0, frame)
                    if (preRollCount < 2) preRollCount++
                    if (mode == Mode.PTT || muted) gate.reset()
                }
                wasSending = send
                if (++meterTick >= 5) {
                    meterTick = 0
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
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { effects.add(it) }
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
    }
}
