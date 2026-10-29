package com.mamaddoom.ridetalk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {

    private fun sequence(vararg steps: Pair<Boolean, Long>): Controls.Trigger? {
        val seq = Controls.VolumeSequence()
        for ((up, at) in steps) seq.press(up, at)
        return seq.resolve()
    }

    @Test
    fun volumeCombinations() {
        assertEquals(Controls.Trigger.VOL_UP_DOWN, sequence(true to 0L, false to 200L))
        assertEquals(Controls.Trigger.VOL_DOWN_UP, sequence(false to 0L, true to 300L))
        assertEquals(Controls.Trigger.VOL_UP_3, sequence(true to 0L, true to 150L, true to 300L))
        assertEquals(Controls.Trigger.VOL_DOWN_4, sequence(false to 0L, false to 150L, false to 300L, false to 450L))
    }

    @Test
    fun singlePressIsJustVolume() {
        assertNull(sequence(true to 0L))
        assertNull(sequence(true to 0L, true to 200L))
    }

    @Test
    fun slowPressesStartOver() {
        val seq = Controls.VolumeSequence()
        assertTrue(seq.press(true, 0))
        // Too slow: the down starts a fresh sequence instead of making "up, down".
        assertTrue(seq.press(false, Controls.WINDOW_MS + 100))
        assertNull(seq.resolve())
    }

    @Test
    fun headsetPresses() {
        assertEquals(Controls.Trigger.HEADSET_1, Controls.headsetTrigger(1, long = false))
        assertEquals(Controls.Trigger.HEADSET_4, Controls.headsetTrigger(4, long = false))
        assertEquals(Controls.Trigger.HEADSET_4, Controls.headsetTrigger(6, long = false))
        assertEquals(Controls.Trigger.HEADSET_LONG, Controls.headsetTrigger(1, long = true))
    }

    @Test
    fun presetsCoverEveryTrigger() {
        for (p in Controls.Preset.values()) assertEquals(Controls.Trigger.values().size, p.map.size)
        assertTrue(Controls.Preset.HEADSET_ONLY.map.filterKeys { it.isVolume }.values.all { it == Controls.Action.NONE })
    }
}
