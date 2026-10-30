package com.mamaddoom.ridetalk

import android.content.Context
import android.net.Uri
import android.os.Build

/** Persistent user preferences. Plain SharedPreferences; reads are cheap and cached. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("ridetalk", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * Updating over an older build kept preferences whose meaning has since
     * changed (old "preset" numbers, the old call-mode default, ...), so an
     * update behaved differently from a fresh install. When the stored schema
     * is older than [SCHEMA], everything except the rider's own data (name,
     * group code, playlist, look and language) goes back to today's defaults.
     */
    private fun migrate() {
        if (p.getInt("schema", 0) >= SCHEMA) return
        val keep = setOf("name", "group", "playlist", "trackIndex", "shuffle", "theme", "lang", "onboarded")
        val e = p.edit()
        for (k in p.all.keys) if (k !in keep) e.remove(k)
        e.putInt("schema", SCHEMA)
        e.commit()
    }

    // ------------------------------------------------------------- look & language

    /** First-launch choices made (theme and language). */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** true = blue theme, false = pink. */
    var blueTheme: Boolean
        get() = p.getString("theme", "pink") == "blue"
        set(v) = p.edit().putString("theme", if (v) "blue" else "pink").apply()

    /** "fa" or "en"; empty = follow the phone. */
    var language: String
        get() = p.getString("lang", "").orEmpty()
        // Written at once: the activity restarts right after to apply it.
        set(v) {
            p.edit().putString("lang", v).commit()
        }

    /** Spoken announcements ("music mode", "Sara joined") instead of beeps, when the phone can speak. */
    var voicePrompts: Boolean
        get() = p.getBoolean("voicePrompts", true)
        set(v) = p.edit().putBoolean("voicePrompts", v).apply()

    var name: String
        get() = p.getString("name", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        set(v) = p.edit().putString("name", v.trim().take(24)).apply()

    /** true = push-to-talk, false = open mic with voice activity detection. */
    var ptt: Boolean
        get() = p.getBoolean("ptt", false)
        set(v) = p.edit().putBoolean("ptt", v).apply()

    /** VOX sensitivity 0..100; higher opens the mic on quieter speech. */
    var voxSensitivity: Int
        get() = p.getInt("vox", 55)
        set(v) = p.edit().putInt("vox", v).apply()

    /** Mic gain in percent (50..300). */
    var micGain: Int
        get() = p.getInt("micGain", 100)
        set(v) = p.edit().putInt("micGain", v).apply()

    /** Voice volume in percent (0..200). */
    var voiceVolume: Int
        get() = p.getInt("voiceVol", 100)
        set(v) = p.edit().putInt("voiceVol", v).apply()

    /** Music volume in percent (0..100). */
    var musicVolume: Int
        get() = p.getInt("musicVol", 85)
        set(v) = p.edit().putInt("musicVol", v).apply()

    var ducking: Boolean
        get() = p.getBoolean("duck", true)
        set(v) = p.edit().putBoolean("duck", v).apply()

    /**
     * true = call mode: headset mic always open over SCO (music at call quality).
     * false = music-quality mode: A2DP stereo, the mic opens on tap (default).
     */
    var headsetMic: Boolean
        get() = p.getBoolean("sco", false)
        set(v) = p.edit().putBoolean("sco", v).apply()

    /** Noise reduction strength 0..100. */
    var noiseReduction: Int
        get() = p.getInt("nr", 60)
        set(v) = p.edit().putInt("nr", v).apply()

    /** Short tones in the headset when a rider joins or leaves. */
    var cues: Boolean
        get() = p.getBoolean("cues", true)
        set(v) = p.edit().putBoolean("cues", v).apply()

    /** Optional group code; empty = open group, otherwise packets are encrypted with it. */
    var groupCode: String
        get() = p.getString("group", "").orEmpty()
        set(v) = p.edit().putString("group", v.trim().take(32)).apply()

    /** Hold the mic down while others talk, to stop speaker/mic feedback loops. */
    var antiFeedback: Boolean
        get() = p.getBoolean("antiFeedback", true)
        set(v) = p.edit().putBoolean("antiFeedback", v).apply()

    /** Keep the screen on while the driving screen is open. */
    var driveKeepScreenOn: Boolean
        get() = p.getBoolean("driveScreenOn", false)
        set(v) = p.edit().putBoolean("driveScreenOn", v).apply()

    /** Noise suppression on the voices you hear (cleans the other rider's wind). */
    var rxDenoise: Boolean
        get() = p.getBoolean("rxDenoise", true)
        set(v) = p.edit().putBoolean("rxDenoise", v).apply()

    /** Turn incoming voices down while you talk, so your own voice can't echo back. */
    var echoGuard: Boolean
        get() = p.getBoolean("echoGuard", true)
        set(v) = p.edit().putBoolean("echoGuard", v).apply()

    /** Standby while alone, auto-off after 30 minutes alone. */
    var batterySaver: Boolean
        get() = p.getBoolean("batterySaver", true)
        set(v) = p.edit().putBoolean("batterySaver", v).apply()

    /** Extra music delay on this phone (0..400 ms) to line up two different headsets. */
    var syncDelayMs: Int
        get() = p.getInt("syncDelay", 0)
        set(v) = p.edit().putInt("syncDelay", v).apply()

    var playlist: List<Uri>
        get() = p.getString("playlist", "").orEmpty().split('\n').filter { it.isNotEmpty() }.map(Uri::parse)
        set(v) = p.edit().putString("playlist", v.joinToString("\n")).apply()

    var trackIndex: Int
        get() = p.getInt("trackIndex", 0)
        set(v) = p.edit().putInt("trackIndex", v).apply()

    var shuffle: Boolean
        get() = p.getBoolean("shuffle", false)
        set(v) = p.edit().putBoolean("shuffle", v).apply()

    // ------------------------------------------------------------- hands-free controls

    fun gesture(t: Controls.Trigger): Controls.Action =
        p.getString("g_${t.name}", null)
            ?.let { n -> Controls.Action.values().firstOrNull { it.name == n } }
            ?: t.default

    fun setGesture(t: Controls.Trigger, a: Controls.Action) =
        p.edit().putString("g_${t.name}", a.name).apply()

    fun applyPreset(preset: Controls.Preset) {
        val e = p.edit()
        for ((t, a) in preset.map) e.putString("g_${t.name}", a.name)
        e.apply()
    }

    /** The preset the current mapping matches, or null when customised. */
    val gesturePreset: Controls.Preset?
        get() = Controls.Preset.values().firstOrNull { pr -> pr.map.all { (t, a) -> gesture(t) == a } }

    /** True when any phone volume-key combination does something. */
    val volumeGestures: Boolean
        get() = Controls.Trigger.values().any { it.isVolume && gesture(it) != Controls.Action.NONE }

    // ------------------------------------------------------------- trip setup

    /**
     * What this phone does. With two phones (one earbud from each headset):
     * MUSIC plays only music in full stereo quality; TALK carries the
     * intercom (headset mic + the other riders' voices) and never music.
     */
    enum class Role { NORMAL, TALK, MUSIC }

    /** Two-phone setup chosen on the trip setup card (older "preset 3" means the same). */
    var twoPhones: Boolean
        get() = p.getBoolean("twoPhones", p.getInt("preset", 1) == 3)
        set(v) = p.edit().putBoolean("twoPhones", v).apply()

    /** Two-phone setup: true on the talk phone, false on the music phone. */
    var isTalkPhone: Boolean
        get() = p.getBoolean("twoPhoneMic", false)
        set(v) = p.edit().putBoolean("twoPhoneMic", v).apply()

    val role: Role
        get() = when {
            !twoPhones -> Role.NORMAL
            isTalkPhone -> Role.TALK
            else -> Role.MUSIC
        }

    /** One earbud in the ear: fold stereo into both channels so nothing is lost. */
    val monoOutput: Boolean
        get() = role == Role.MUSIC

    companion object {
        /** Bump when a stored preference changes meaning; older stores are reset on update. */
        const val SCHEMA = 3
    }
}
