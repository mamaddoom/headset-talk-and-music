package com.mamaddoom.ridetalk.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.mamaddoom.ridetalk.net.Protocol

/** Per-remote-rider receive state. Created by the engine, read by the mixer. */
class PeerAudio {
    val voice = JitterBuffer<ByteArray>(minTarget = 2, maxTarget = 8)
    val decoder = VoiceDecoder()
    /** Last voice sample, for seamless 16 -> 32 kHz upsampling across frames. */
    var lastSample = 0f
    /** Consecutive concealed frames; after a few we fade to silence. */
    var lossRun = 0
    @Volatile var levelDb = -120f
    @Volatile var lastVoiceAtMs = 0L
}

/**
 * Pulls one 20 ms period from every jitter buffer, mixes voice and music with
 * ducking, and writes it to a single AudioTrack. The blocking write paces the
 * loop off the audio hardware clock, so there are no timers to drift.
 */
class Mixer(
    /** Voice-call audio attributes (needed while the headset mic is in use). */
    private val sco: Boolean,
    /**
     * 32 kHz stereo output. False only for a classic Bluetooth call link (SCO),
     * which carries at most 16 kHz mono; LE Audio, wired and speaker get full rate.
     */
    private val highQuality: Boolean,
    private val preferredOutput: AudioDeviceInfo?,
    private val peers: () -> Collection<PeerAudio>,
    private val music: JitterBuffer<ShortArray>,
) {
    @Volatile var voiceVolume = 1f
    @Volatile var musicVolume = 0.6f
    @Volatile var duckingEnabled = true
    /** Set by the engine while we are transmitting, so our own talking also ducks music. */
    @Volatile var localTalking = false
    /** Two-tone cues mixed into the output: mic ready, rider joined, rider left. */
    enum class Cue(val first: Double, val second: Double, val level: Double) {
        READY(880.0, 1320.0, 0.25),
        JOIN(660.0, 990.0, 0.18),
        LEAVE(990.0, 660.0, 0.18),
    }

    @Volatile private var pendingCue: Cue? = null

    /** Plays [cue] at the next period (a newer cue replaces one not yet started). */
    fun cue(cue: Cue) {
        pendingCue = cue
    }

    /** Set while any remote rider is audible; used by anti-feedback on the mic side. */
    @Volatile var remoteTalking = false
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    val outRate = if (highQuality) Protocol.MUSIC_RATE else Protocol.VOICE_RATE
    private val channels = if (highQuality) 2 else 1
    private val periodFrames = outRate / 50 // 20 ms

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "rt-mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun buildTrack(): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(outRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val periodBytes = periodFrames * channels * 2
        val attrs = AudioAttributes.Builder()
            .setUsage(if (sco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (sco) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(outRate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, periodBytes * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = try {
            buildTrack()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack failed", e)
            running = false
            return
        }
        preferredOutput?.let { track.preferredDevice = it }

        val voicePcm = ShortArray(Protocol.VOICE_FRAME)
        val mix = FloatArray(periodFrames * channels)
        val out = ShortArray(periodFrames * channels)
        // Music squeezed into a Bluetooth call link (16 kHz mono): steep 8th-order
        // anti-alias filter right up to the band edge, then a presence lift and
        // gentle compression so it doesn't sound dull and stays audible over wind.
        val decimLp1 = LowPass4(Protocol.MUSIC_RATE, 7200.0)
        val decimLp2 = LowPass4(Protocol.MUSIC_RATE, 7200.0)
        val presence = Biquad.peaking(Protocol.VOICE_RATE, 3200.0, 3.0, 0.8)
        val air = Biquad.peaking(Protocol.VOICE_RATE, 6500.0, 2.5, 1.0)
        val squeeze = Compressor(Protocol.VOICE_RATE)
        var duckGain = 1f
        val duckAttack = 1f - Math.exp(-1.0 / (0.03 * outRate)).toFloat()
        val duckRelease = 1f - Math.exp(-1.0 / (0.6 * outRate)).toFloat()
        var beepLeft = 0
        var beepPhase = 0.0
        var beep = Cue.READY

        try {
            track.play()
            while (running) {
                mix.fill(0f)
                val now = System.currentTimeMillis()
                var anyTalking = localTalking
                var remote = false

                // --- voice from every peer ---
                val vv = voiceVolume
                for (p in peers()) {
                    if (!pullVoice(p, voicePcm)) continue
                    val level = Pcm.rmsDb(voicePcm)
                    p.levelDb = level
                    if (level > -48f) p.lastVoiceAtMs = now
                    if (now - p.lastVoiceAtMs < 400) {
                        anyTalking = true
                        remote = true
                    }
                    if (channels == 1) {
                        for (i in 0 until periodFrames) mix[i] += voicePcm[i] / 32768f * vv
                    } else {
                        // 16 kHz -> 32 kHz, linear interpolation, duplicated to L/R.
                        var prev = p.lastSample
                        for (i in 0 until Protocol.VOICE_FRAME) {
                            val s = voicePcm[i] / 32768f * vv
                            val mid = (prev + s) * 0.5f
                            val o = i * 4
                            mix[o] += mid; mix[o + 1] += mid
                            mix[o + 2] += s; mix[o + 3] += s
                            prev = s
                        }
                        p.lastSample = prev
                    }
                }

                // --- shared music: two 10 ms packets per period ---
                val mv = musicVolume
                val duckTarget = if (duckingEnabled && anyTalking) DUCK_LEVEL else 1f
                for (half in 0 until 2) {
                    val pkt = music.poll() ?: continue
                    if (channels == 2) {
                        val base = half * Protocol.MUSIC_SAMPLES
                        for (i in 0 until Protocol.MUSIC_SAMPLES) {
                            if (i and 1 == 0) duckGain += (duckTarget - duckGain) *
                                (if (duckTarget < duckGain) duckAttack else duckRelease)
                            mix[base + i] += pkt[i] / 32768f * mv * duckGain
                        }
                    } else {
                        val base = half * (Protocol.MUSIC_FRAMES / 2)
                        for (i in 0 until Protocol.MUSIC_FRAMES / 2) {
                            // Downmix to mono, low-pass, then keep every second sample.
                            decimLp2.process(decimLp1.process((pkt[i * 4] + pkt[i * 4 + 1]) / 65536f))
                            val d = decimLp2.process(decimLp1.process((pkt[i * 4 + 2] + pkt[i * 4 + 3]) / 65536f))
                            val y = squeeze.process(air.process(presence.process(d)))
                            duckGain += (duckTarget - duckGain) *
                                (if (duckTarget < duckGain) duckAttack else duckRelease)
                            mix[base + i] += y * mv * duckGain
                        }
                    }
                }

                remoteTalking = remote
                pendingCue?.let {
                    pendingCue = null
                    beep = it
                    beepLeft = outRate * 18 / 100 // 180 ms
                }
                if (beepLeft > 0) {
                    val total = outRate * 18 / 100
                    for (f in 0 until periodFrames) {
                        if (beepLeft <= 0) break
                        val t = total - beepLeft
                        val freq = if (t < total / 2) beep.first else beep.second
                        beepPhase += 2 * Math.PI * freq / outRate
                        // 10 ms fade in/out avoids clicks.
                        val env = minOf(1f, minOf(t, beepLeft) / (outRate / 100f))
                        val v = (Math.sin(beepPhase) * beep.level * env).toFloat()
                        for (c in 0 until channels) mix[f * channels + c] += v
                        beepLeft--
                    }
                }

                for (i in mix.indices) out[i] = Pcm.toShort(mix[i])
                var off = 0
                while (off < out.size && running) {
                    val n = track.write(out, off, out.size - off)
                    if (n < 0) throw IllegalStateException("write error $n")
                    off += n
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback stopped", e)
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    /** Decodes the next voice frame for [p] into [pcm]; false means silence. */
    private fun pullVoice(p: PeerAudio, pcm: ShortArray): Boolean {
        val packet = p.voice.poll()
        return when (p.voice.lastResult) {
            JitterBuffer.Result.FRAME -> {
                p.lossRun = 0
                p.decoder.decode(packet!!, pcm)
            }
            JitterBuffer.Result.LOST -> {
                p.lossRun++
                // Prefer FEC from the following packet; otherwise Opus PLC.
                val next = p.voice.peek()
                if (next != null) p.decoder.decodeFec(next, pcm)
                else if (p.lossRun <= MAX_PLC_FRAMES) p.decoder.conceal(pcm)
                else false
            }
            JitterBuffer.Result.SILENT -> {
                // One concealed frame lets Opus fade the tail out instead of clicking.
                if (p.lossRun == 0) {
                    p.lossRun = MAX_PLC_FRAMES + 1
                    p.decoder.conceal(pcm)
                } else {
                    false
                }
            }
        }
    }

    private companion object {
        const val TAG = "Mixer"
        const val DUCK_LEVEL = 0.25f
        const val MAX_PLC_FRAMES = 3
    }
}
