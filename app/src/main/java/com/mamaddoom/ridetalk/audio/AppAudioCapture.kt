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
 * through Android's playback-capture API and turns it into 10 ms music packets.
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
                .setBufferSizeInBytes(maxOf(minBuf, IN_FRAMES * 4 * 8))
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

        val raw = ShortArray(IN_FRAMES * 2)
        val f = FloatArray(IN_FRAMES * 2)
        val lpL = LowPass4(IN_RATE, 14500.0)
        val lpR = LowPass4(IN_RATE, 14500.0)
        val resampler = StreamResampler(IN_RATE, Protocol.MUSIC_RATE, 2)
        val packet = ShortArray(Protocol.MUSIC_SAMPLES)
        var fill = 0
        var silentPackets = 0
        var packetSilent = true

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
                for (i in 0 until IN_FRAMES) {
                    f[i * 2] = lpL.process(raw[i * 2] / 32768f)
                    f[i * 2 + 1] = lpR.process(raw[i * 2 + 1] / 32768f)
                }
                val n = resampler.process(f, IN_FRAMES)
                val o = resampler.output
                for (i in 0 until n * 2) {
                    val s = Pcm.clampToShort(o[i])
                    if (abs(s.toInt()) > SILENCE) packetSilent = false
                    packet[fill++] = s
                    if (fill == packet.size) {
                        fill = 0
                        silentPackets = if (packetSilent) silentPackets + 1 else 0
                        packetSilent = true
                        // Nothing playing: stop sending so the channel frees up for others.
                        val audible = silentPackets < SILENT_HOLD
                        hasSignal = audible
                        if (audible) sink(packet.copyOf())
                    }
                }
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
        const val IN_RATE = 48000
        const val IN_FRAMES = 480 // 10 ms
        const val SILENCE = 8
        const val SILENT_HOLD = 50 // 0.5 s of digital silence
    }
}
