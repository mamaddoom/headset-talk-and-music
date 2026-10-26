package com.mamaddoom.ridetalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private class Clock(var ms: Double = 0.0)

    private val clock = Clock()
    private fun jb(min: Int = 2, max: Int = 15) = JitterBuffer<Int>(min, max, capacity = 64, periodMs = 20.0) { clock.ms }

    /** Simulates 20 ms ticks: [arrivals] maps tick -> seqs arriving at that tick; returns what was played. */
    private fun simulate(b: JitterBuffer<Int>, ticks: Int, start: Int = 0, arrivals: (Int) -> List<Int>): List<Int?> {
        val out = ArrayList<Int?>()
        for (t in 0 until ticks) {
            clock.ms = (start + t) * 20.0
            for (s in arrivals(t)) b.put(s, s)
            out += b.poll()
        }
        return out
    }

    @Test
    fun steadyStreamPlaysInOrderAtMinimumDelay() {
        val b = jb()
        val played = simulate(b, 200) { listOf(it) }.filterNotNull()
        assertEquals((0 until played.size).toList(), played)
        assertTrue("lost only the initial buffering", played.size >= 197)
        assertEquals(2, b.target)
    }

    @Test
    fun reordersOutOfOrderPackets() {
        val b = jb()
        clock.ms = 0.0
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
    fun wifiPowerSaveBurstsAreAbsorbedNotDropped() {
        // Every second the link holds packets for 200 ms, then delivers them all at once.
        val b = jb()
        val played = simulate(b, 1500) { t ->
            val inStall = t % 50 in 40..49
            when {
                inStall -> emptyList()
                t % 50 == 0 && t > 0 -> (t - 10..t).toList() // the backlog plus the current packet
                else -> listOf(t)
            }
        }
        // After learning (first 10 s), nothing may be lost any more.
        val late = played.drop(500)
        val gaps = late.count { it == null }
        println("burst link: target=${b.target} frames, jitter=${"%.0f".format(b.jitterMs)} ms, gaps after learning=$gaps")
        assertTrue("target should cover the 200 ms bursts, was ${b.target}", b.target >= 11)
        assertEquals(0, gaps)
        assertEquals(0L, b.dropped)
    }

    @Test
    fun talkPausesDoNotInflateTheDelay() {
        val b = jb()
        // 1 s talk, 1 s silence (sender sends nothing), repeated; sequence numbers stay contiguous.
        var seq = 0
        simulate(b, 1000) { t -> if ((t / 50) % 2 == 0) listOf(seq++) else emptyList() }
        assertEquals(2, b.target)
    }

    @Test
    fun delayShrinksBackOnceTheLinkIsClean() {
        val b = jb()
        simulate(b, 300) { t -> when { t % 50 in 40..49 -> emptyList(); t % 50 == 0 && t > 0 -> (t - 10..t).toList(); else -> listOf(t) } }
        val high = b.target
        simulate(b, 2000, start = 300) { listOf(300 + it) } // 40 s clean
        println("after bursts target=$high, after 40 s clean target=${b.target}")
        assertTrue(high >= 11)
        assertTrue("should settle back near the minimum, was ${b.target}", b.target <= 3)
    }

    @Test
    fun clockDriftIsCorrectedGentlyWithoutResync() {
        // Sender's clock runs 1 % fast: an extra packet every 100 ticks.
        val b = jb()
        var seq = 0
        var credit = 0.0
        val played = simulate(b, 6000) { _ ->
            credit += 1.01
            val list = ArrayList<Int>()
            while (credit >= 1.0) { list += seq++; credit -= 1.0 }
            list
        }
        println("drift: dropped=${b.dropped}, target=${b.target}")
        assertTrue("drift should be corrected by dropping single frames", b.dropped in 40L..80L)
        // Never a long run of silence once playing.
        val firstPlay = played.indexOfFirst { it != null }
        assertTrue(played.drop(firstPlay).none { it == null })
    }

    @Test
    fun latePacketsAreDropped() {
        val b = jb()
        b.put(5, 5); b.put(6, 6)
        assertEquals(5, b.poll())
        assertEquals(6, b.poll())
        b.put(4, 4)
        b.put(7, 7); b.put(8, 8)
        assertEquals(7, b.poll())
    }

    @Test
    fun senderRestartResyncs() {
        val b = jb()
        for (s in 1000 until 1004) b.put(s, s)
        repeat(4) { b.poll() }
        b.put(0, 0); b.put(1, 1)
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
