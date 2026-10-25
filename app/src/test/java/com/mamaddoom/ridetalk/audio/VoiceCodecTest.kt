package com.mamaddoom.ridetalk.audio

import com.mamaddoom.ridetalk.net.Protocol
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceCodecTest {
    private fun tone(frame: Int) = ShortArray(Protocol.VOICE_FRAME) {
        val t = frame * Protocol.VOICE_FRAME + it
        (sin(2 * PI * 300 * t / Protocol.VOICE_RATE) * 8000).toInt().toShort()
    }

    @Test
    fun encodesSmallPacketsAndDecodesSignal() {
        val enc = VoiceEncoder()
        val dec = VoiceDecoder()
        val packet = ByteArray(400)
        val out = ShortArray(Protocol.VOICE_FRAME)
        var maxLen = 0
        var lastLevel = -120f
        for (f in 0 until 50) {
            val n = enc.encode(tone(f), packet)
            assertTrue(n in 1..400)
            maxLen = maxOf(maxLen, n)
            assertTrue(dec.decode(packet.copyOf(n), out))
            lastLevel = Pcm.rmsDb(out)
        }
        assertTrue("24 kbit/s * 20 ms ≈ 60 bytes, got $maxLen", maxLen < 160)
        assertTrue("decoded tone should be audible, got $lastLevel dB", lastLevel > -25f)
    }

    @Test
    fun concealmentAndFecProduceAudio() {
        val enc = VoiceEncoder()
        val dec = VoiceDecoder()
        val packets = (0 until 30).map { f ->
            val b = ByteArray(400)
            b.copyOf(enc.encode(tone(f), b))
        }
        val out = ShortArray(Protocol.VOICE_FRAME)
        for (f in 0 until 20) dec.decode(packets[f], out)
        // Frame 20 lost: recover from FEC in frame 21.
        assertTrue(dec.decodeFec(packets[21], out))
        assertTrue("FEC output should not be silent", Pcm.rmsDb(out) > -40f)
        dec.decode(packets[21], out)
        // Frame 22 lost with nothing after it: PLC.
        assertTrue(dec.conceal(out))
    }
}
