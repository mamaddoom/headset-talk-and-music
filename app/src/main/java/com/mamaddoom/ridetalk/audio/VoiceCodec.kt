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
        setPacketLossPercent(10)
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
