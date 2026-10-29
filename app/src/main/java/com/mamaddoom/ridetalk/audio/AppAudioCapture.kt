package com.mamaddoom.ridetalk.audio

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import com.mamaddoom.ridetalk.net.Protocol
import kotlin.math.abs

/**
 * Captures what other apps are playing (Spotify, YouTube Music, a podcast app…)
 * through Android's playback-capture API and turns it into 20 ms music packets.
 *
 * The phone that captures already hears the original app through its own
 * audio route, so the packets are only sent to the peers, not mixed locally
 * (that would play everything twice with an echo).
 *
 * Our own output is excluded so the intercom never captures itself. Apps that
 * opt out of capture (some DRM/streaming apps) simply produce silence.
 */
@TargetApi(29)
class AppAudioCapture(
    private val projection: MediaProjection,
    private val sink: (ShortArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** True while something audible is being captured (for the UI). */
    @Volatile var hasSignal = false
        private set
    @Volatile var failed = false
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "rt-app-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        hasSignal = false
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before capture is offered
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(IN_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, Protocol.MUSIC_SAMPLES * 2 * 4))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "playback capture unavailable", e)
            failed = true
            running = false
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            failed = true
            running = false
            return
        }

        // Captured at the music rate already: 20 ms reads go out as-is, no resampling.
        val raw = ShortArray(Protocol.MUSIC_SAMPLES)
        var silentPackets = 0

        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < raw.size && running) {
                    val n = record.read(raw, got, raw.size - got)
                    if (n < 0) throw IllegalStateException("read error $n")
                    got += n
                }
                if (!running) break
                var silent = true
                for (v in raw) if (abs(v.toInt()) > SILENCE) { silent = false; break }
                silentPackets = if (silent) silentPackets + 1 else 0
                // Nothing playing: stop sending so the channel frees up for others.
                val audible = silentPackets < SILENT_HOLD
                hasSignal = audible
                if (audible) sink(raw.copyOf())
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback capture stopped", e)
            failed = true
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            hasSignal = false
        }
    }

    private companion object {
        const val TAG = "AppAudioCapture"
        const val IN_RATE = Protocol.MUSIC_RATE
        const val SILENCE = 8
        const val SILENT_HOLD = 25 // 0.5 s of digital silence
    }
}
