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
        get() = p.getInt("musicVol", 60)
        set(v) = p.edit().putInt("musicVol", v).apply()

    var ducking: Boolean
        get() = p.getBoolean("duck", true)
        set(v) = p.edit().putBoolean("duck", v).apply()

    /** true = headset mic over SCO (talk + music), false = A2DP music-only, listen-only. */
    var headsetMic: Boolean
        get() = p.getBoolean("sco", true)
        set(v) = p.edit().putBoolean("sco", v).apply()

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

    /** Maps sensitivity 0..100 to a VOX threshold of -25..-55 dBFS. */
    fun voxThresholdDb(): Float = -25f - voxSensitivity * 0.3f
}
