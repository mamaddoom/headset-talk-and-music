package com.mamaddoom.ridetalk

/**
 * Hands-free controls: what each headset press pattern and phone volume-key
 * combination does. Every trigger can be mapped to any [Action] in settings.
 */
object Controls {

    enum class Action {
        NONE,
        /** Talk / end talk (call mode: push-to-talk or mute; music phone: play/pause). */
        TALK,
        /** Music quality <-> mic always on, with a distinct tone for each. */
        SWITCH_MODE,
        MUTE,
        PLAY_PAUSE,
        NEXT_TRACK,
        PREV_TRACK,
        MUSIC_UP,
        MUSIC_DOWN,
    }

    /** Out of the box only one tap does something (talk); every other shortcut starts empty. */
    enum class Trigger(val default: Action = Action.NONE) {
        HEADSET_1(Action.TALK),
        HEADSET_2,
        HEADSET_3,
        HEADSET_4,
        HEADSET_LONG,
        /** Most earbuds send "next" for a double tap on the bud. */
        HEADSET_NEXT,
        /** ...and "previous" for a triple tap. */
        HEADSET_PREV,
        VOL_UP_DOWN,
        VOL_DOWN_UP,
        VOL_UP_3,
        VOL_DOWN_3,
        VOL_UP_4,
        VOL_DOWN_4,
        ;

        val isVolume get() = name.startsWith("VOL_")
    }

    /** Ready-made layouts. */
    enum class Preset(val map: Map<Trigger, Action>) {
        /** The default: one tap talks, nothing else. */
        SIMPLE(Trigger.values().associateWith { it.default }),
        /** Everything mapped: mode switch, songs, mute, volume-key combos. */
        FULL(
            Trigger.values().associateWith { it.default } + mapOf(
                Trigger.HEADSET_2 to Action.SWITCH_MODE,
                Trigger.HEADSET_3 to Action.NEXT_TRACK,
                Trigger.HEADSET_4 to Action.MUTE,
                Trigger.HEADSET_LONG to Action.PLAY_PAUSE,
                Trigger.HEADSET_NEXT to Action.SWITCH_MODE,
                Trigger.HEADSET_PREV to Action.NEXT_TRACK,
                Trigger.VOL_UP_DOWN to Action.SWITCH_MODE,
                Trigger.VOL_DOWN_UP to Action.TALK,
            ),
        ),
        /** Music first: taps drive the player, talking stays on one tap. */
        MUSIC_FIRST(
            Trigger.values().associateWith { it.default } + mapOf(
                Trigger.HEADSET_2 to Action.NEXT_TRACK,
                Trigger.HEADSET_3 to Action.PREV_TRACK,
                Trigger.HEADSET_LONG to Action.PLAY_PAUSE,
                Trigger.HEADSET_NEXT to Action.NEXT_TRACK,
                Trigger.HEADSET_PREV to Action.PREV_TRACK,
                Trigger.VOL_UP_3 to Action.MUSIC_UP,
                Trigger.VOL_DOWN_3 to Action.MUSIC_DOWN,
            ),
        ),
    }

    /**
     * Turns phone volume steps into a trigger. Feed each step with [press];
     * once [WINDOW_MS] pass without a new step, [resolve] names the pattern
     * (or null for an ordinary volume change).
     */
    class VolumeSequence {
        private val steps = StringBuilder()
        private var lastMs = Long.MIN_VALUE

        /** Returns true if this step starts a new sequence. */
        fun press(up: Boolean, nowMs: Long): Boolean {
            val fresh = steps.isEmpty() || nowMs - lastMs > WINDOW_MS
            if (fresh) steps.setLength(0)
            if (steps.length < 8) steps.append(if (up) 'U' else 'D')
            lastMs = nowMs
            return fresh
        }

        fun resolve(): Trigger? {
            val s = steps.toString()
            steps.setLength(0)
            return when (s) {
                "UD" -> Trigger.VOL_UP_DOWN
                "DU" -> Trigger.VOL_DOWN_UP
                "UUU" -> Trigger.VOL_UP_3
                "DDD" -> Trigger.VOL_DOWN_3
                "UUUU" -> Trigger.VOL_UP_4
                "DDDD" -> Trigger.VOL_DOWN_4
                else -> null
            }
        }
    }

    /** Headset press count (and long press) to a trigger. */
    fun headsetTrigger(presses: Int, long: Boolean): Trigger = when {
        long -> Trigger.HEADSET_LONG
        presses <= 1 -> Trigger.HEADSET_1
        presses == 2 -> Trigger.HEADSET_2
        presses == 3 -> Trigger.HEADSET_3
        else -> Trigger.HEADSET_4
    }

    /** Time allowed between presses of one pattern. */
    const val WINDOW_MS = 600L
    /** Headset presses resolve faster: earbud taps come quickly. */
    const val HEADSET_WINDOW_MS = 450L
    /** Holding the headset button this long is a long press. */
    const val LONG_PRESS_MS = 700L
}
