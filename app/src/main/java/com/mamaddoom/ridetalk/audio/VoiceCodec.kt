package com.mamaddoom.ridetalk.audio

import com.mamaddoom.ridetalk.net.Protocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/** Opus voice encoder: 16 kHz mono, 20 ms frames, in-band FEC for Wi-Fi loss. */
class VoiceEncoder(bitrate: Int = 24000) {
    private val enc = OpusEncoder(Protocol.VOICE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(5)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        setUseInbandFEC(true)
        // Tuned for a fading highway link: more in-band FEC, on top of packet redundancy.
        setPacketLossPercent(20)
        setUseVBR(true)
    }

    /** Encodes one [Protocol.VOICE_FRAME] frame; returns the packet length. */
    fun encode(pcm: ShortArray, out: ByteArray): Int =
        enc.encode(pcm, 0, Protocol.VOICE_FRAME, out, 0, out.size)

    fun reset() = enc.resetState()
}

/** One per remote peer; holds Opus state for concealment and FEC. */
class VoiceDecoder {
    private val dec = OpusDecoder(Protocol.VOICE_RATE, 1)

    fun decode(packet: ByteArray, out: ShortArray): Boolean = run(packet, false, out)

    /** Recovers the frame *before* [next] from the FEC data carried inside [next]. */
    fun decodeFec(next: ByteArray, out: ShortArray): Boolean = run(next, true, out)

    /** Packet-loss concealment: synthesises a plausible continuation. */
    fun conceal(out: ShortArray): Boolean = run(null, false, out)

    private fun run(packet: ByteArray?, fec: Boolean, out: ShortArray): Boolean = try {
        val n = dec.decode(packet, 0, packet?.size ?: 0, out, 0, Protocol.VOICE_FRAME, fec)
        if (n < Protocol.VOICE_FRAME) out.fill(0, maxOf(n, 0), Protocol.VOICE_FRAME)
        n > 0
    } catch (_: Exception) {
        out.fill(0)
        false
    }

    fun reset() = dec.resetState()
}

/**
 * Opus music encoder: 48 kHz stereo, 20 ms, ~128 kbit/s VBR. Roughly a tenth
 * of the airtime raw PCM needed, which keeps a weak Wi-Fi link alive.
 */
class MusicEncoder(bitrate: Int = 128000) {
    private val enc = OpusEncoder(Protocol.MUSIC_RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        setBitrate(bitrate)
        setComplexity(5)
        setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC)
        setUseVBR(true)
    }
    private val out = ByteArray(MAX_BYTES)

    /** Target bitrate in bit/s; can change between packets. */
    var bitrate: Int = bitrate
        set(v) {
            if (v == field) return
            field = v
            enc.setBitrate(v)
        }

    /** Encodes one [Protocol.MUSIC_FRAMES]-frame stereo packet; returns the Opus bytes. */
    fun encode(pcm: ShortArray): ByteArray? = try {
        val n = enc.encode(pcm, 0, Protocol.MUSIC_FRAMES, out, 0, out.size)
        if (n > 0) out.copyOf(n) else null
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Caps one frame so two (current + redundant) always fit in a datagram. */
        const val MAX_BYTES = 640
    }
}

/** Opus music decoder with packet-loss concealment. */
class MusicDecoder {
    private val dec = OpusDecoder(Protocol.MUSIC_RATE, 2)

    fun decode(packet: ByteArray, out: ShortArray): Boolean = run(packet, out)

    fun conceal(out: ShortArray): Boolean = run(null, out)

    private fun run(packet: ByteArray?, out: ShortArray): Boolean = try {
        val n = dec.decode(packet, 0, packet?.size ?: 0, out, 0, Protocol.MUSIC_FRAMES, false)
        if (n < Protocol.MUSIC_FRAMES) out.fill(0, maxOf(n, 0) * 2, Protocol.MUSIC_SAMPLES)
        n > 0
    } catch (_: Exception) {
        out.fill(0)
        false
    }

    fun reset() = dec.resetState()
}
