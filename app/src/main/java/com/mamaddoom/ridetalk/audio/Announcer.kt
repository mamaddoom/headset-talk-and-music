package com.mamaddoom.ridetalk.audio

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Short spoken announcements ("music mode", "Sara joined") made with the
 * phone's own text-to-speech engine, offline. Speech is rendered to PCM and
 * handed to [onSpeech], which mixes it into the headset like any other sound,
 * so it works on both the music link and the call link.
 *
 * Uses the app language when the phone has a voice for it, otherwise English;
 * with no usable voice at all [ready] stays false and the caller beeps instead.
 */
class Announcer(
    context: Context,
    private val language: String,
    /** String resources rendered as soon as the voice is ready, so they play instantly later. */
    private val warmUp: IntArray,
    private val onSpeech: (samples: FloatArray, rate: Int) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(app.cacheDir, "speech").apply { mkdirs() }
    private val cache = ConcurrentHashMap<String, Pcm16>()
    /** Texts waiting to be spoken once rendered, with the time they were asked for. */
    private val waiting = ConcurrentHashMap<String, Long>()
    private var tts: TextToSpeech? = null
    private var seq = 0

    /** Strings in the language the voice actually speaks. */
    @Volatile var res: Context = app
        private set
    @Volatile var ready = false
        private set
    /** True if the voice speaks Persian (rider names can be read as written). */
    @Volatile var persian = false
        private set

    class Pcm16(val samples: FloatArray, val rate: Int)

    init {
        main.post { open() }
    }

    private fun open() {
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            val wantFa = language == "fa" || (language.isEmpty() && Locale.getDefault().language == "fa")
            val fa = Locale("fa", "IR")
            val locale = when {
                wantFa && available(t, fa) -> fa
                available(t, Locale.US) -> Locale.US
                available(t, Locale.ENGLISH) -> Locale.ENGLISH
                else -> return@TextToSpeech
            }
            t.language = locale
            t.setSpeechRate(1.1f)
            persian = locale.language == "fa"
            res = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) = rendered(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) {
                    File(dir, "$id.wav").delete()
                }
            })
            ready = true
            for (id in warmUp) render(res.getString(id))
        }
    }

    private fun available(t: TextToSpeech, l: Locale) = try {
        t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE
    } catch (_: Exception) {
        false
    }

    /** Speaks [text] now if rendered, or as soon as it is (dropped if that takes over 3 s). */
    fun say(text: String) {
        if (!ready) return
        val pcm = cache[text]
        if (pcm != null) {
            onSpeech(pcm.samples, pcm.rate)
            return
        }
        waiting[text] = System.currentTimeMillis()
        main.post { render(text) }
    }

    private val ids = ConcurrentHashMap<String, String>()

    private fun render(text: String) {
        val t = tts ?: return
        if (cache.containsKey(text) || ids.containsValue(text)) return
        val id = "u${seq++}"
        ids[id] = text
        val r = t.synthesizeToFile(text, Bundle(), File(dir, "$id.wav"), id)
        if (r != TextToSpeech.SUCCESS) ids.remove(id)
    }

    private fun rendered(id: String) {
        val text = ids.remove(id) ?: return
        val file = File(dir, "$id.wav")
        val pcm = try {
            readWav(file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "speech file unreadable", e)
            null
        } finally {
            file.delete()
        }
        if (pcm == null) return
        cache[text] = pcm
        val askedAt = waiting.remove(text) ?: return
        if (System.currentTimeMillis() - askedAt < 3000) onSpeech(pcm.samples, pcm.rate)
    }

    fun release() {
        ready = false
        main.post {
            tts?.shutdown()
            tts = null
        }
    }

    companion object {
        private const val TAG = "Announcer"

        /** Mono 16-bit PCM WAV to floats, trimmed of leading/trailing silence and normalised. */
        fun readWav(bytes: ByteArray): Pcm16? {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val len = b.getInt(pos + 4)
                val body = pos + 8
                if (len < 0) return null
                if (id == "fmt " && len >= 16) {
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                } else if (id == "data") {
                    if (bits != 16 || channels < 1 || rate <= 0) return null
                    val end = minOf(bytes.size, body + len)
                    val frames = (end - body) / (2 * channels)
                    val out = FloatArray(frames) { i -> b.getShort(body + i * 2 * channels) / 32768f }
                    return trimAndNormalise(out, rate)
                }
                pos = body + len + (len and 1)
            }
            return null
        }

        private fun trimAndNormalise(x: FloatArray, rate: Int): Pcm16? {
            val thr = 0.01f
            var a = x.indexOfFirst { kotlin.math.abs(it) > thr }
            var z = x.indexOfLast { kotlin.math.abs(it) > thr }
            if (a < 0 || z <= a) return null
            a = maxOf(0, a - rate / 100)
            z = minOf(x.size - 1, z + rate / 20)
            val out = x.copyOfRange(a, z + 1)
            val peak = out.maxOf { kotlin.math.abs(it) }
            val g = 0.6f / peak
            for (i in out.indices) out[i] *= g
            return Pcm16(out, rate)
        }
    }
}
