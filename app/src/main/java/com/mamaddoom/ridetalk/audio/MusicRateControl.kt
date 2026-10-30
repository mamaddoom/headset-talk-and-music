package com.mamaddoom.ridetalk.audio

/**
 * Picks the shared-music bitrate from the loss the listeners report. A weak
 * link (long distance, overtaking a truck) steps down quickly so the music
 * stops eating the airtime voice needs; a clean link steps back up slowly.
 */
class MusicRateControl {

    class Level(val bitrate: Int, val redundancy: Int)

    var level = 0
        private set
    private var goodSinceMs = -1L
    private var lastDownMs = Long.MIN_VALUE / 2

    val current: Level get() = LEVELS[level]

    /**
     * [worstLossPct]: the highest recent loss any listener reported, or null
     * when nobody reported (no listeners, or older builds). Returns true when
     * the level changed.
     */
    fun update(worstLossPct: Int?, nowMs: Long): Boolean {
        val before = level
        when {
            worstLossPct == null -> goodSinceMs = -1L
            worstLossPct >= BAD_PCT -> {
                goodSinceMs = -1L
                if (nowMs - lastDownMs >= DOWN_HOLD_MS) {
                    level = (level + if (worstLossPct >= VERY_BAD_PCT) 2 else 1).coerceAtMost(LEVELS.size - 1)
                    lastDownMs = nowMs
                }
            }
            worstLossPct <= GOOD_PCT -> {
                if (goodSinceMs < 0) goodSinceMs = nowMs
                if (level > 0 && nowMs - goodSinceMs >= UP_AFTER_MS) {
                    level--
                    goodSinceMs = nowMs
                }
            }
            else -> goodSinceMs = -1L
        }
        return level != before
    }

    fun reset() {
        level = 0
        goodSinceMs = -1L
        lastDownMs = Long.MIN_VALUE / 2
    }

    companion object {
        val LEVELS = listOf(
            Level(128_000, 2),
            Level(96_000, 2),
            Level(64_000, 3),
            Level(40_000, 3),
        )
        const val GOOD_PCT = 2
        const val BAD_PCT = 8
        const val VERY_BAD_PCT = 25
        const val DOWN_HOLD_MS = 2000L
        const val UP_AFTER_MS = 10_000L
    }
}
