package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AnnouncerTest {

    private fun wav(samples: ShortArray, rate: Int, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 4 else 0
        val b = ByteBuffer.allocate(44 + extra + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        if (extraChunk) b.put("LIST".toByteArray()).putInt(4).putInt(0)
        b.put("data".toByteArray()).putInt(samples.size * 2)
        for (s in samples) b.putShort(s)
        return b.array()
    }

    @Test
    fun readsTrimsAndNormalisesSpeech() {
        val rate = 22050
        // 0.2 s silence, 0.3 s tone, 0.2 s silence.
        val pcm = ShortArray(rate * 7 / 10) { i ->
            if (i in rate / 5 until rate / 2) (Math.sin(i * 0.2) * 3000).toInt().toShort() else 0
        }
        val clip = Announcer.readWav(wav(pcm, rate, extraChunk = true))
        assertNotNull(clip)
        clip!!
        assertEquals(rate, clip.rate)
        // Silence trimmed (only a little padding kept).
        assertTrue(clip.samples.size < rate * 45 / 100)
        assertEquals(0.6f, clip.samples.maxOf { kotlin.math.abs(it) }, 0.01f)
    }

    @Test
    fun rejectsGarbageAndSilence() {
        assertNull(Announcer.readWav(ByteArray(10)))
        assertNull(Announcer.readWav(wav(ShortArray(1000), 16000)))
    }

    @Test
    fun resampleKeepsDuration() {
        val x = FloatArray(16000) { Math.sin(it * 0.1).toFloat() }
        assertEquals(48000, Pcm.resample(x, 16000, 48000).size)
        assertEquals(16000, Pcm.resample(FloatArray(24000), 24000, 16000).size)
    }
}
