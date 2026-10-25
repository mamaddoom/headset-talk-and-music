package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private fun jb(min: Int = 2, max: Int = 8) = JitterBuffer<Int>(min, max, capacity = 64, shrinkAfter = 50)

    @Test
    fun buffersUntilTargetThenPlaysInOrder() {
        val b = jb()
        b.put(10, 10)
        assertNull(b.poll())
        assertEquals(JitterBuffer.Result.SILENT, b.lastResult)
        b.put(11, 11)
        assertEquals(10, b.poll())
        assertEquals(11, b.poll())
    }

    @Test
    fun reordersOutOfOrderPackets() {
        val b = jb()
        b.put(1, 1); b.put(3, 3); b.put(2, 2)
        assertEquals(1, b.poll())
        assertEquals(2, b.poll())
        assertEquals(3, b.poll())
    }

    @Test
    fun reportsLossAndExposesNextFrameForFec() {
        val b = jb()
        b.put(0, 0); b.put(2, 2)
        assertEquals(0, b.poll())
        assertNull(b.poll())
        assertEquals(JitterBuffer.Result.LOST, b.lastResult)
        // After the lost slot, peek shows the frame that carries FEC for it.
        assertEquals(2, b.peek())
        assertEquals(2, b.poll())
        assertEquals(1L, b.concealed)
    }

    @Test
    fun peekBeforeLossPollReturnsFollowingFrame() {
        val b = jb()
        b.put(0, 0); b.put(1, 1); b.put(3, 3); b.put(4, 4)
        assertEquals(0, b.poll())
        assertEquals(1, b.poll())
        assertNull(b.poll()) // seq 2 missing
        assertEquals(JitterBuffer.Result.LOST, b.lastResult)
        assertEquals(3, b.peek())
    }

    @Test
    fun dropsLatePackets() {
        val b = jb()
        b.put(5, 5); b.put(6, 6)
        assertEquals(5, b.poll())
        assertEquals(6, b.poll())
        b.put(4, 4) // already played past it
        b.put(7, 7); b.put(8, 8)
        assertEquals(7, b.poll())
    }

    @Test
    fun latePacketsAfterUnderrunGrowTarget() {
        val b = jb(min = 2)
        b.put(0, 0); b.put(1, 1)
        b.poll(); b.poll()
        assertNull(b.poll()) // ran dry
        b.put(2, 2) // stream continues right away: jitter, not silence
        assertEquals(3, b.target)
    }

    @Test
    fun talkPauseDoesNotGrowTarget() {
        val b = jb(min = 2)
        b.put(0, 0); b.put(1, 1)
        b.poll(); b.poll()
        repeat(50) { b.poll() } // speaker paused (VOX closed) for a second
        b.put(2, 2)
        assertEquals(2, b.target)
    }

    @Test
    fun targetShrinksBackAfterStablePeriod() {
        val b = jb(min = 2)
        b.put(0, 0); b.put(1, 1); b.poll(); b.poll(); b.poll(); b.put(2, 2)
        assertEquals(3, b.target)
        var seq = 3
        repeat(200) {
            b.put(seq, seq); seq++
            b.poll()
        }
        assertEquals(2, b.target)
    }

    @Test
    fun skipsAheadWhenTooDeep() {
        val b = jb(min = 2)
        for (s in 0 until 20) b.put(s, s)
        // depth 20 > target + slack: skip so only `target` frames remain buffered
        val first = b.poll()!!
        assertTrue("expected to skip old frames, got $first", first >= 17)
    }

    @Test
    fun senderRestartResyncs() {
        val b = jb()
        for (s in 1000 until 1004) b.put(s, s)
        repeat(4) { b.poll() }
        b.put(0, 0); b.put(1, 1) // sender restarted counter far behind
        assertEquals(0, b.poll())
        assertEquals(1, b.poll())
    }

    @Test
    fun handlesSequenceWrapAround() {
        val b = jb()
        b.put(Int.MAX_VALUE, 1)
        b.put(Int.MIN_VALUE, 2)
        assertEquals(1, b.poll())
        assertEquals(2, b.poll())
    }

    @Test
    fun raisingMinTargetAppliesImmediately() {
        val b = jb(min = 2)
        b.minTarget = 5
        assertEquals(5, b.target)
        for (s in 0 until 4) b.put(s, s)
        assertNull("still buffering below new target", b.poll())
        b.put(4, 4)
        assertEquals(0, b.poll())
    }
}
