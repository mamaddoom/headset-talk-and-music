package com.mamaddoom.ridetalk

import android.content.Context
import android.net.Uri
import android.os.Build

/** Persistent user preferences. Plain SharedPreferences; reads are cheap and cached. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("ridetalk", Context.MODE_PRIVATE)

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

}
