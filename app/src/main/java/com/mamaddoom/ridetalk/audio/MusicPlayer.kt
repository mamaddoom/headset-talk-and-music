package com.mamaddoom.ridetalk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import com.mamaddoom.ridetalk.net.Protocol
import java.nio.ByteOrder

/**
 * Decodes local audio files and emits 10 ms packets of 32 kHz stereo PCM at
 * real-time pace. The engine sends every packet to the peers and also feeds
 * it into our own music jitter buffer, so both phones play the same packet
 * after the same buffering delay and stay in sync.
 */
class MusicPlayer(private val context: Context, private val sink: Sink) {
    interface Sink {
        fun onMusicPacket(seq: Int, pcm: ShortArray)
        /** Main-thread-agnostic: called from the player thread. */
        fun onMusicState()
    }

    val playlist = ArrayList<Uri>()
    @Volatile var index = 0
        private set
    /** A track is loaded and the player thread is alive (playing or paused). */
    @Volatile var active = false
        private set
    @Volatile var paused = false
        private set
    val playing get() = active && !paused
    @Volatile var title = ""
        private set
    /** Set when no track in the playlist could be decoded. */
    @Volatile var error: Boolean = false
        private set

    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var skip = 0
    private var seq = 0

    @Synchronized
    fun setPlaylist(uris: List<Uri>) {
        stop()
        playlist.clear()
        playlist.addAll(uris)
        index = 0
        title = if (uris.isEmpty()) "" else displayName(uris[0])
        sink.onMusicState()
    }

    @Synchronized
    fun play() {
        if (playlist.isEmpty()) return
        if (active) {
            paused = false
        } else {
            stopRequested = false
            paused = false
            active = true
            error = false
            thread = Thread(::loop, "rt-music").also { it.start() }
        }
        sink.onMusicState()
    }

    @Synchronized
    fun pause() {
        if (!active) return
        paused = true
        sink.onMusicState()
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        thread?.interrupt()
        thread?.join(1500)
        thread = null
        active = false
        paused = false
        sink.onMusicState()
    }

    fun toggle() = if (playing) pause() else play()

    fun next() = jump(+1)
    fun previous() = jump(-1)

    @Synchronized
    private fun jump(delta: Int) {
        if (playlist.isEmpty()) return
        if (active) {
            paused = false
            skip = delta // the player thread moves on at the next packet
        } else {
            index = Math.floorMod(index + delta, playlist.size)
            title = displayName(playlist[index])
            sink.onMusicState()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val packet = ShortArray(Protocol.MUSIC_SAMPLES)
        var fill = 0
        var failures = 0
        val periodNs = 10_000_000L
        var deadline = System.nanoTime()

        // Emits one packet once it is full, sleeping until its slot in real time.
        fun emit(l: Float, r: Float): Boolean {
            packet[fill++] = Pcm.toShort(l)
            packet[fill++] = Pcm.toShort(r)
            if (fill < packet.size) return true
            fill = 0
            while (paused && !stopRequested && skip == 0) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return false
                }
                deadline = System.nanoTime()
            }
            deadline += periodNs
            val wait = deadline - System.nanoTime()
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    return false
                }
            } else if (wait < -200_000_000L) {
                deadline = System.nanoTime() // we were stalled; don't burst to catch up
            }
            sink.onMusicPacket(seq++, packet.copyOf())
            return !stopRequested && skip == 0
        }

        while (!stopRequested) {
            val uri = playlist.getOrNull(index) ?: break
            title = displayName(uri)
            sink.onMusicState()
            val result = try {
                decode(uri, ::emit)
            } catch (e: Exception) {
                Log.w(TAG, "decode failed: $uri", e)
                false
            }
            if (stopRequested) break
            if (!result && skip == 0) {
                failures++
                if (failures >= playlist.size) {
                    error = true
                    break
                }
            } else {
                failures = 0
            }
            val d = if (skip != 0) skip else 1
            skip = 0
            index = Math.floorMod(index + d, playlist.size)
            deadline = System.nanoTime()
        }
        active = false
        paused = false
        sink.onMusicState()
    }

    /** Returns true if the track played to the end (or was skipped). */
    private fun decode(uri: Uri, emit: (Float, Float) -> Boolean): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = StreamResampler(rate, Protocol.MUSIC_RATE, 2)
            var lpL = Biquad.lowPass(rate, cutoff(rate))
            var lpR = Biquad.lowPass(rate, cutoff(rate))
            var isFloat = false
            var stereo = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (!stopRequested && skip == 0) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = StreamResampler(rate, Protocol.MUSIC_RATE, 2)
                    lpL = Biquad.lowPass(rate, cutoff(rate))
                    lpR = Biquad.lowPass(rate, cutoff(rate))
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (outIdx < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val buf = c.getOutputBuffer(outIdx)!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.order(ByteOrder.nativeOrder())
                val frames: Int
                if (isFloat) {
                    val fl = buf.asFloatBuffer()
                    frames = fl.remaining() / ch
                    if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                    for (i in 0 until frames) {
                        val l = fl.get(i * ch)
                        val r = if (ch > 1) fl.get(i * ch + 1) else l
                        stereo[i * 2] = lpL.process(l)
                        stereo[i * 2 + 1] = lpR.process(r)
                    }
                } else {
                    val sh = buf.asShortBuffer()
                    frames = sh.remaining() / ch
                    if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                    for (i in 0 until frames) {
                        val l = sh.get(i * ch) / 32768f
                        val r = if (ch > 1) sh.get(i * ch + 1) / 32768f else l
                        stereo[i * 2] = lpL.process(l)
                        stereo[i * 2 + 1] = lpR.process(r)
                    }
                }
                c.releaseOutputBuffer(outIdx, false)
                val n = resampler.process(stereo, frames)
                val o = resampler.output
                for (i in 0 until n) {
                    if (!emit(o[i * 2], o[i * 2 + 1])) return true
                }
                if (eos) return true
            }
            return true
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            ex.release()
        }
    }

    fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (_: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    private fun cutoff(rate: Int) = minOf(14500.0, rate * 0.45)

    private companion object {
        const val TAG = "MusicPlayer"
    }
}
