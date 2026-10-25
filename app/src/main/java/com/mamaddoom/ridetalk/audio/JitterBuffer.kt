package com.mamaddoom.ridetalk.audio

/**
 * Reorders incoming frames by sequence number and smooths out network jitter.
 *
 * The producer (network thread) calls [put]; the consumer (audio thread) calls
 * [poll] once per frame period. The playout delay adapts: an underrun caused by
 * late packets grows the target by one frame, and a long stable stretch shrinks
 * it again, so a clean link stays at the minimum latency. A sender that simply
 * stops (VOX closed, music paused) does not count as jitter.
 *
 * Clock drift between the two phones is absorbed by dropping frames when the
 * buffer grows too deep and by re-buffering when it runs dry.
 */
class JitterBuffer<T : Any>(
    minTarget: Int,
    private val maxTarget: Int,
    private val capacity: Int = 64,
    /** Polls without underrun before the target is lowered again. */
    private val shrinkAfter: Int = 500,
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
    private var stablePolls = 0
    /** Polls since the buffer ran dry; -1 when not in an underrun. */
    private var dryPolls = -1

    var target = minTarget
        private set

    /** Lowest playout delay in frames. Raising it takes effect immediately. */
    @get:Synchronized @set:Synchronized
    var minTarget = minTarget
        set(v) {
            field = v
            if (target < v) target = v
        }

    var lastResult = Result.SILENT
        private set

    /** Frames played from real data / concealed (lost or late). For link quality. */
    var played = 0L
        private set
    var concealed = 0L
        private set

    @Synchronized
    fun put(seq: Int, frame: T) {
        if (!started) {
            started = true
            nextSeq = seq
            highestSeq = seq
        } else {
            val behind = nextSeq - seq
            if (behind > 0) {
                // Late frame, or the sender restarted its counter.
                if (behind > capacity * 2) reset(seq) else return
            } else if (seq - nextSeq >= capacity) {
                // Far ahead: we fell hopelessly behind (or the stream jumped). Resync.
                reset(seq)
            }
        }
        if (dryPolls in 0..LATE_WINDOW && seq == nextSeq) {
            // The stream continued right after we ran dry: packets were late.
            if (target < maxTarget) target++
            stablePolls = 0
        }
        dryPolls = -1
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
        val depth = highestSeq - nextSeq + 1
        if (buffering) {
            if (dryPolls >= 0) dryPolls++
            if (depth < target) return null
            buffering = false
        }
        if (depth <= 0) {
            buffering = true
            dryPolls = 0
            return null
        }
        // Too deep (drift or a burst): skip old frames to get back to target.
        if (depth > target + DRIFT_SLACK) {
            val skipTo = highestSeq - target + 1
            while (nextSeq - skipTo < 0) {
                slots[Math.floorMod(nextSeq, capacity)] = null
                nextSeq++
            }
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
        if (++stablePolls >= shrinkAfter && target > minTarget) {
            target--
            stablePolls = 0
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
        dryPolls = -1
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
        dryPolls = -1
        nextSeq = seq
        highestSeq = seq
        buffering = true
    }

    private companion object {
        const val DRIFT_SLACK = 4
        const val LATE_WINDOW = 3
    }
}
