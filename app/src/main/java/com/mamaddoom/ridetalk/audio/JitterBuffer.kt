package com.mamaddoom.ridetalk.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Reorders incoming frames by sequence number and absorbs network jitter.
 *
 * The producer (network thread) calls [put]; the consumer (audio thread) calls
 * [poll] once per frame period.
 *
 * Playout delay comes from measured arrival times: for each packet we compute
 * how late it is relative to the earliest packet of the current window
 * (arrival time minus its sequence slot). The spread of those delays is the
 * jitter we must cover; a decaying peak of it sets the target depth. Wi-Fi
 * power-save bursts (packets held for 100–300 ms, then delivered at once) are
 * therefore absorbed instead of being thrown away, while a clean link settles
 * back to the minimum delay within ~20 s.
 *
 * Clock drift between phones is handled gently: only a *sustained* excess
 * depth drops one frame at a time; a pause by the sender (VOX, music paused)
 * simply re-buffers and is not mistaken for jitter.
 */
class JitterBuffer<T : Any>(
    minTarget: Int,
    private val maxTarget: Int,
    private val capacity: Int = 64,
    private val periodMs: Double = 20.0,
    private val clockMs: () -> Double = { System.nanoTime() / 1_000_000.0 },
) {
    enum class Result {
        /** [poll] returned a frame. */
        FRAME,
        /** A frame in the middle of the stream is missing: conceal it (PLC/FEC). */
        LOST,
        /** Nothing to play: buffering, or the sender is quiet. */
        SILENT,
    }

    private val slots = arrayOfNulls<Any>(capacity)
    private val slotSeq = IntArray(capacity)

    private var started = false
    private var buffering = true
    private var nextSeq = 0
    private var highestSeq = 0
    private var excessPolls = 0

    // Delay-spread estimator.
    private var anchored = false
    private var refSeq = 0
    private var refTime = 0.0
    private var winStart = 0.0
    private var winMin = 0.0
    private var winMax = 0.0
    private var peakMs = 0.0
    private var lastArrival = Double.NEGATIVE_INFINITY

    /** Lowest playout delay in frames. Raising it takes effect immediately. */
    @get:Synchronized @set:Synchronized
    var minTarget = minTarget

    /** Current playout delay in frames. */
    @get:Synchronized
    val target: Int
        get() = targetFrames()

    var lastResult = Result.SILENT
        private set

    /** Frames played from real data / concealed (lost or late). For link quality. */
    var played = 0L
        private set
    var concealed = 0L
        private set
    /** Frames dropped to correct clock drift. */
    var dropped = 0L
        private set

    /**
     * Adds [frame] for [seq]. [timed] is false for redundant copies carried in a
     * later packet: they fill holes but must not count as late arrivals.
     */
    @Synchronized
    fun put(seq: Int, frame: T, timed: Boolean = true) {
        if (!started) {
            if (!timed) return // never start a stream from a redundant copy
            started = true
            nextSeq = seq
            highestSeq = seq
            measure(seq)
        } else {
            val behind = nextSeq - seq
            if (behind > capacity * 2 || seq - nextSeq >= capacity) {
                if (!timed) return
                // The sender restarted its counter, or we fell hopelessly behind: resync,
                // and measure the new stream from scratch.
                reset(seq)
                anchored = false
                measure(seq)
            } else {
                if (timed) measure(seq) // late packets count as jitter too
                if (behind > 0) return
                val i = Math.floorMod(seq, capacity)
                if (!timed && slotSeq[i] == seq && slots[i] != null) return // already have it
            }
        }
        val i = Math.floorMod(seq, capacity)
        slots[i] = frame
        slotSeq[i] = seq
        if (seq - highestSeq > 0) highestSeq = seq
    }

    /** Returns the next frame, or null; see [lastResult] for why it is null. */
    @Synchronized
    fun poll(): T? {
        lastResult = Result.SILENT
        if (!started) return null
        val target = targetFrames()
        var depth = highestSeq - nextSeq + 1
        if (buffering) {
            if (depth < target) return null
            buffering = false
        }
        if (depth <= 0) {
            buffering = true
            excessPolls = 0
            return null
        }
        val slack = max(2, target / 2)
        if (depth > maxTarget + 2 * slack || depth >= capacity - 2) {
            // Hopelessly deep (long stall then a flood): jump back to the target.
            while (depth > target) {
                slots[Math.floorMod(nextSeq, capacity)] = null
                nextSeq++
                depth--
                dropped++
            }
            excessPolls = 0
        } else if (depth > target + slack) {
            // Sustained excess means drift: drop single frames, slowly.
            if (++excessPolls >= EXCESS_POLLS) {
                slots[Math.floorMod(nextSeq, capacity)] = null
                nextSeq++
                dropped++
                excessPolls = EXCESS_POLLS / 2
            }
        } else {
            excessPolls = 0
        }
        val frame = take(nextSeq)
        nextSeq++
        if (frame != null) {
            played++
            lastResult = Result.FRAME
        } else {
            concealed++
            lastResult = Result.LOST
        }
        return frame
    }

    /** The frame that the next [poll] will return, if it has arrived. Used for Opus FEC. */
    @Synchronized
    fun peek(): T? {
        val i = Math.floorMod(nextSeq, capacity)
        @Suppress("UNCHECKED_CAST")
        return if (started && slotSeq[i] == nextSeq) slots[i] as T? else null
    }

    @Synchronized
    fun clear() {
        slots.fill(null)
        started = false
        buffering = true
        anchored = false
        excessPolls = 0
    }

    /** 0..1 fraction of frames that arrived in time. */
    @Synchronized
    fun quality(): Float {
        val total = played + concealed
        return if (total == 0L) 1f else played.toFloat() / total
    }

    @Synchronized
    fun resetStats() {
        played = 0
        concealed = 0
        dropped = 0
    }

    /** Measured arrival-delay spread (ms) currently covered. For diagnostics. */
    @get:Synchronized
    val jitterMs: Double
        get() = max(peakMs, winMax - winMin)

    private fun targetFrames(): Int {
        val spread = max(peakMs, winMax - winMin)
        val wanted = ceil(spread / periodMs).toInt() + 1 // +1 frame of safety
        return min(maxTarget, max(minTarget, wanted))
    }

    private fun measure(seq: Int) {
        val now = clockMs()
        // A long silence before this packet is a new talk spurt: start a fresh window.
        if (!anchored || now - lastArrival > SPURT_GAP_MS) anchor(seq, now)
        lastArrival = now
        val rel = (now - refTime) - (seq - refSeq) * periodMs
        if (rel < winMin) winMin = rel
        if (rel > winMax) winMax = rel
        if (now - winStart > WINDOW_MS) {
            // Fold this window into the decaying peak and start the next one.
            peakMs = max(peakMs * PEAK_DECAY, winMax - winMin)
            anchor(seq, now)
        }
    }

    private fun anchor(seq: Int, now: Double) {
        if (anchored) peakMs = max(peakMs * PEAK_DECAY, winMax - winMin)
        anchored = true
        refSeq = seq
        refTime = now
        winStart = now
        winMin = 0.0
        winMax = 0.0
    }

    private fun take(seq: Int): T? {
        val i = Math.floorMod(seq, capacity)
        @Suppress("UNCHECKED_CAST")
        val frame = if (slotSeq[i] == seq) slots[i] as T? else null
        slots[i] = null
        return frame
    }

    private fun reset(seq: Int) {
        slots.fill(null)
        nextSeq = seq
        highestSeq = seq
        buffering = true
        excessPolls = 0
    }

    private companion object {
        /** Polls of continuous excess depth before one frame is dropped (~0.5 s of voice). */
        const val EXCESS_POLLS = 25
        /** A gap longer than this before a packet starts a new talk spurt. */
        const val SPURT_GAP_MS = 400.0
        /** Length of one measuring window; the peak decays by [PEAK_DECAY] per window. */
        const val WINDOW_MS = 4000.0
        const val PEAK_DECAY = 0.8
    }
}
