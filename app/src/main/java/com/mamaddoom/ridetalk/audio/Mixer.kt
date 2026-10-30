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
    val voice = JitterBuffer<ByteArray>(minTarget = 2, maxTarget = 15)
    /** Receive-side leveler: brings every rider to the same loudness (older senders, quiet mics). */
    val leveler = SpeechAgc(targetDb = -18f, maxGainDb = 9f, minGainDb = -6f)
    val decoder = VoiceDecoder()
    /**
     * Receive-side noise suppression: cleans what reaches *your* ear even if
     * the other rider's phone let wind through. Starts from a quiet floor
     * (voice only arrives while someone talks, so there is no noise-only lead-in).
     */
    val denoiser = VoiceDenoiser(Protocol.VOICE_RATE, learnInitialNoise = false).apply { strength = 50 }
    val floatBuf = FloatArray(Protocol.VOICE_FRAME)
    /** Last voice sample, for seamless 16 -> 48 kHz upsampling across frames. */
    var lastSample = 0f
    /** Consecutive concealed frames; after a few we fade to silence. */
    var lossRun = 0
    /** Smoothed echo-guard gain (1 = open). */
    var echoGain = 1f
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
     * 48 kHz stereo output. False only for a classic Bluetooth call link (SCO),
     * which carries at most 16 kHz mono; LE Audio, wired and speaker get full rate.
     */
    private val highQuality: Boolean,
    private val preferredOutput: AudioDeviceInfo?,
    private val peers: () -> Collection<PeerAudio>,
    private val music: JitterBuffer<ByteArray>,
) {
    @Volatile var voiceVolume = 1f
    @Volatile var musicVolume = 0.85f
    @Volatile var duckingEnabled = true
    /** Noise suppression on incoming voices. */
    @Volatile var rxDenoise = true
    /**
     * Echo guard: while we talk (and briefly after, to cover the network delay)
     * incoming voices are turned down, so our own voice picked up by a nearby
     * mic — a pillion's phone, a rider right beside us — doesn't come back as echo.
     */
    @Volatile var echoGuard = true
    /** Last time we sent a voice frame (set by the engine for every frame). */
    @Volatile var localSpeechAtMs = 0L
    /**
     * Music phone of a two-phone setup: voices are not played here, but the
     * engine stamps this when someone talks so the music still ducks.
     */
    @Volatile var voiceElsewhereAtMs = 0L
    /** One earbud only: fold stereo to mono on both channels so nothing is lost. */
    @Volatile var mono = false
    /** Set by the engine while we are transmitting, so our own talking also ducks music. */
    @Volatile var localTalking = false

    /** Two-tone cues mixed into the output. */
    enum class Cue(val first: Double, val second: Double, val level: Double) {
        READY(880.0, 1320.0, 0.25),
        JOIN(660.0, 990.0, 0.18),
        LEAVE(990.0, 660.0, 0.18),
        /** Switched to music-quality mode: bright, rising. */
        MODE_MUSIC(784.0, 1568.0, 0.22),
        /** Switched to always-on mic: low, falling. */
        MODE_CALL(523.0, 392.0, 0.25),
        /** Mic muted: two low beeps. */
        MUTED(330.0, 330.0, 0.25),
        /** Mic back on: two high beeps. */
        UNMUTED(660.0, 660.0, 0.2),
        /** Music volume step. */
        TICK(1200.0, 1200.0, 0.12),
    }

    @Volatile private var pendingCue: Cue? = null

    /** Plays [cue] at the next period (a newer cue replaces one not yet started). */
    fun cue(cue: Cue) {
        pendingCue = cue
    }

    @Volatile private var pendingSpeech: FloatArray? = null

    /**
     * Plays a spoken announcement ([samples] mono at [rate]) over everything,
     * with the music ducked under it. A newer one replaces one still playing.
     */
    fun say(samples: FloatArray, rate: Int) {
        pendingSpeech = Pcm.resample(samples, rate, outRate)
    }

    /** Set while any remote rider is audible; used by anti-feedback on the mic side. */
    @Volatile var remoteTalking = false
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    val outRate = if (highQuality) Protocol.MUSIC_RATE else Protocol.VOICE_RATE
    private val channels = if (highQuality) 2 else 1
    private val periodFrames = outRate / 50 // 20 ms
    /** Output samples per voice sample (48 kHz / 16 kHz = 3, or 1 on a call link). */
    private val upsample = outRate / Protocol.VOICE_RATE

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
        val musicPcm = ShortArray(Protocol.MUSIC_SAMPLES)
        val musicDecoder = MusicDecoder()
        var musicLossRun = 0
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
        var speech: FloatArray? = null
        var speechPos = 0

        try {
            track.play()
            while (running) {
                mix.fill(0f)
                val now = System.currentTimeMillis()
                pendingSpeech?.let {
                    pendingSpeech = null
                    speech = it
                    speechPos = 0
                }
                var anyTalking = localTalking || now - voiceElsewhereAtMs < 400 || speech != null
                var remote = false

                // --- voice from every peer ---
                val vvBase = voiceVolume
                val denoise = rxDenoise
                for (p in peers()) {
                    if (!pullVoice(p, voicePcm)) continue
                    val f = p.floatBuf
                    for (i in 0 until Protocol.VOICE_FRAME) f[i] = voicePcm[i] / 32768f
                    if (denoise) p.denoiser.process(f, Protocol.VOICE_FRAME)
                    val level = Pcm.rmsDb(f, Protocol.VOICE_FRAME)
                    p.levelDb = level
                    if (level > -48f) p.lastVoiceAtMs = now
                    // Echo guard: our own voice returns after the network + jitter delay.
                    val echoWindow = ECHO_HOLD_MS + p.voice.target * 20L
                    val echoTarget = if (echoGuard && now - localSpeechAtMs < echoWindow) ECHO_LEVEL else 1f
                    p.echoGain += (echoTarget - p.echoGain) * (if (echoTarget < p.echoGain) 0.6f else 0.15f)
                    val vv = vvBase * p.leveler.update(level, level > -45f) * p.echoGain
                    if (now - p.lastVoiceAtMs < 400) {
                        anyTalking = true
                        remote = true
                    }
                    if (upsample == 1) {
                        for (i in 0 until periodFrames) mix[i] += f[i] * vv
                    } else {
                        // 16 kHz -> 48 kHz, linear interpolation, duplicated to L/R.
                        var prev = p.lastSample
                        var o = 0
                        for (i in 0 until Protocol.VOICE_FRAME) {
                            val s = f[i] * vv
                            for (k in 1..upsample) {
                                val v = prev + (s - prev) * k / upsample
                                mix[o] += v
                                mix[o + 1] += v
                                o += 2
                            }
                            prev = s
                        }
                        p.lastSample = prev
                    }
                }

                // --- shared music: one 20 ms Opus packet per period ---
                val mv = musicVolume
                val duckTarget = if (duckingEnabled && anyTalking) DUCK_LEVEL else 1f
                val pkt = music.poll()
                val haveMusic = when (music.lastResult) {
                    JitterBuffer.Result.FRAME -> {
                        musicLossRun = 0
                        musicDecoder.decode(pkt!!, musicPcm)
                    }
                    JitterBuffer.Result.LOST -> {
                        if (++musicLossRun <= MAX_PLC_FRAMES) musicDecoder.conceal(musicPcm) else false
                    }
                    JitterBuffer.Result.SILENT -> false
                }
                if (haveMusic) {
                    if (channels == 2) {
                        for (i in 0 until Protocol.MUSIC_SAMPLES) {
                            if (i and 1 == 0) duckGain += (duckTarget - duckGain) *
                                (if (duckTarget < duckGain) duckAttack else duckRelease)
                            mix[i] += musicPcm[i] / 32768f * mv * duckGain
                        }
                    } else {
                        // Downmix to mono, low-pass, then keep every third sample (48 -> 16 kHz).
                        for (i in 0 until periodFrames) {
                            var d = 0f
                            for (k in 0 until 3) {
                                val j = (i * 3 + k) * 2
                                d = decimLp2.process(decimLp1.process((musicPcm[j] + musicPcm[j + 1]) / 65536f))
                            }
                            val y = squeeze.process(air.process(presence.process(d)))
                            duckGain += (duckTarget - duckGain) *
                                (if (duckTarget < duckGain) duckAttack else duckRelease)
                            mix[i] += y * mv * duckGain
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
                    for (fr in 0 until periodFrames) {
                        if (beepLeft <= 0) break
                        val t = total - beepLeft
                        val freq = if (t < total / 2) beep.first else beep.second
                        beepPhase += 2 * Math.PI * freq / outRate
                        // 10 ms fade in/out avoids clicks.
                        val env = minOf(1f, minOf(t, beepLeft) / (outRate / 100f))
                        val v = (Math.sin(beepPhase) * beep.level * env).toFloat()
                        for (c in 0 until channels) mix[fr * channels + c] += v
                        beepLeft--
                    }
                }

                speech?.let { sp ->
                    var fr = 0
                    while (fr < periodFrames && speechPos < sp.size) {
                        val v = sp[speechPos++] * SPEECH_LEVEL
                        for (c in 0 until channels) mix[fr * channels + c] += v
                        fr++
                    }
                    if (speechPos >= sp.size) speech = null
                }

                if (mono && channels == 2) {
                    for (i in 0 until periodFrames) {
                        val m = (mix[i * 2] + mix[i * 2 + 1]) * 0.5f
                        mix[i * 2] = m
                        mix[i * 2 + 1] = m
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
        /** Music under speech: -9 dB keeps the song present but the voice on top. */
        const val DUCK_LEVEL = 0.35f
        const val MAX_PLC_FRAMES = 3
        /** Incoming voice while we talk: -16 dB, enough to hide our own returning voice. */
        const val ECHO_LEVEL = 0.16f
        /** Extra hold after our last word, on top of the peer's jitter delay. */
        const val ECHO_HOLD_MS = 250L
        const val SPEECH_LEVEL = 0.9f
    }
}
