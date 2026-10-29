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

    enum class Trigger(val default: Action) {
        HEADSET_1(Action.TALK),
        HEADSET_2(Action.SWITCH_MODE),
        HEADSET_3(Action.NEXT_TRACK),
        HEADSET_4(Action.MUTE),
        HEADSET_LONG(Action.PLAY_PAUSE),
        /** Most earbuds send "next" for a double tap on the bud. */
        HEADSET_NEXT(Action.SWITCH_MODE),
        /** ...and "previous" for a triple tap. */
        HEADSET_PREV(Action.NEXT_TRACK),
        VOL_UP_DOWN(Action.SWITCH_MODE),
        VOL_DOWN_UP(Action.TALK),
        VOL_UP_3(Action.NONE),
        VOL_DOWN_3(Action.NONE),
        VOL_UP_4(Action.NONE),
        VOL_DOWN_4(Action.NONE),
        ;

        val isVolume get() = name.startsWith("VOL_")
    }

    /** Ready-made layouts; [CUSTOM] means the user changed something. */
    enum class Preset(val map: Map<Trigger, Action>) {
        STANDARD(Trigger.values().associateWith { it.default }),
        /** Headset only, nothing on the phone's volume keys. */
        HEADSET_ONLY(Trigger.values().associateWith { if (it.isVolume) Action.NONE else it.default }),
        /** Music first: taps drive the player, talking stays on one tap. */
        MUSIC_FIRST(
            Trigger.values().associateWith { it.default } + mapOf(
                Trigger.HEADSET_2 to Action.NEXT_TRACK,
                Trigger.HEADSET_3 to Action.PREV_TRACK,
                Trigger.HEADSET_4 to Action.SWITCH_MODE,
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
