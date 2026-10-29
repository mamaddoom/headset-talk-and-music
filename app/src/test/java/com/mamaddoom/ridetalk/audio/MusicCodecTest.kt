package com.mamaddoom.ridetalk.audio

import com.mamaddoom.ridetalk.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

class MusicCodecTest {
    private fun stereoTone(frame: Int) = ShortArray(Protocol.MUSIC_SAMPLES) {
        val t = frame * Protocol.MUSIC_FRAMES + it / 2
        (sin(2 * PI * (if (it % 2 == 0) 440 else 660) * t / Protocol.MUSIC_RATE) * 9000).toInt().toShort()
    }

    @Test
    fun musicIsSmallAndDecodes() {
        val enc = MusicEncoder()
        val dec = MusicDecoder()
        val out = ShortArray(Protocol.MUSIC_SAMPLES)
        var bytes = 0
        var level = -120f
        for (f in 0 until 100) {
            val p = enc.encode(stereoTone(f))!!
            assertTrue(p.size <= MusicEncoder.MAX_BYTES)
            bytes += p.size
            assertTrue(dec.decode(p, out))
            level = Pcm.rmsDb(out)
        }
        val kbps = bytes * 8 / 2.0 / 1000 // 100 frames = 2 s
        println("music: ${"%.0f".format(kbps)} kbit/s (raw PCM would be 1536)")
        assertTrue("bitrate $kbps", kbps < 200)
        assertTrue("decoded music should be audible, $level dB", level > -25f)
    }

    @Test
    fun redundancyRecoversEveryOtherPacketLost() {
        // Sender: voice frames with two redundant copies, exactly as the engine sends them.
        val enc = VoiceEncoder()
        val history = ArrayDeque<ByteArray>()
        val packets = ArrayList<ByteArray>()
        val buf = ByteBuffer.allocate(Protocol.MAX_PACKET)
        for (seq in 0 until 50) {
            val pcm = ShortArray(Protocol.VOICE_FRAME) { (sin(2 * PI * 300 * (seq * 320 + it) / 16000.0) * 8000).toInt().toShort() }
            val b = ByteArray(400)
            history.addFirst(b.copyOf(enc.encode(pcm, b)))
            while (history.size > Protocol.VOICE_REDUNDANCY) history.removeLast()
            val len = Protocol.encodeFrames(buf, Protocol.TYPE_VOICE, 1, seq, history)
            packets += buf.array().copyOf(len)
        }
        // Receiver loses every other packet (50 % loss) and still gets every frame.
        var now = 0.0
        val jb = JitterBuffer<ByteArray>(2, 15, periodMs = 20.0) { now }
        var got = 0
        var lost = 0
        for ((seq, p) in packets.withIndex()) {
            now = seq * 20.0
            if (seq % 2 == 0) {
                val rx = ByteBuffer.wrap(p)
                val h = Protocol.readHeader(rx)!!
                Protocol.decodeFrames(rx)!!.forEachIndexed { k, f -> jb.put(h.seq - k, f, timed = k == 0) }
            }
            jb.poll()
            when (jb.lastResult) {
                JitterBuffer.Result.FRAME -> got++
                JitterBuffer.Result.LOST -> lost++
                JitterBuffer.Result.SILENT -> Unit
            }
        }
        println("50 % packet loss: played $got frames, concealed $lost")
        assertEquals("nothing concealed: redundancy filled every hole", 0, lost)
        assertTrue(got >= 45)

    }
}
