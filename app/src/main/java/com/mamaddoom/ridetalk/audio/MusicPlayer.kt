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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Playlist player: decodes local audio files and emits 10 ms packets of
 * 32 kHz stereo PCM at real-time pace. The engine sends every packet to the
 * peers and also feeds it into our own music jitter buffer, so every phone
 * plays the same packet after the same buffering delay and they stay in sync.
 */
class MusicPlayer(private val context: Context, private val sink: Sink) {
    interface Sink {
        /** Called on the player thread for every 10 ms packet. */
        fun onMusicPacket(pcm: ShortArray)
        /** Called from any thread when playback state or the playlist changes. */
        fun onMusicState()
    }

    class Track(val uri: Uri, val title: String)

    val tracks = CopyOnWriteArrayList<Track>()

    @Volatile var index = 0
        private set
    /** A track is loaded and the player thread is alive (playing or paused). */
    @Volatile var active = false
        private set
    @Volatile var paused = false
        private set
    val playing get() = active && !paused
    @Volatile var shuffle = false
    /** Set when no track in the playlist could be decoded. */
    @Volatile var error = false
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set

    val title: String get() = tracks.getOrNull(index)?.title.orEmpty()

    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    /** Track to switch to at the next packet, or -1. */
    @Volatile private var pendingIndex = -1
    /** Seek target in microseconds, or -1. */
    @Volatile private var seekUs = -1L

    // ------------------------------------------------------------- playlist

    fun setTracks(uris: List<Uri>, startIndex: Int = 0) {
        stop()
        tracks.clear()
        tracks.addAll(uris.map { Track(it, displayName(it)) })
        index = startIndex.coerceIn(0, maxOf(0, tracks.size - 1))
        sink.onMusicState()
    }

    fun add(uris: List<Uri>) {
        val known = tracks.map { it.uri }.toHashSet()
        tracks.addAll(uris.filter { it !in known }.map { Track(it, displayName(it)) })
        error = false
        sink.onMusicState()
    }

    @Synchronized
    fun remove(i: Int) {
        if (i !in tracks.indices) return
        val wasCurrent = i == index
        tracks.removeAt(i)
        when {
            tracks.isEmpty() -> {
                stop()
                index = 0
            }
            i < index -> index--
            wasCurrent -> {
                index = index.coerceAtMost(tracks.size - 1)
                if (active) pendingIndex = index
            }
        }
        sink.onMusicState()
    }

    fun clear() {
        stop()
        tracks.clear()
        index = 0
        sink.onMusicState()
    }

    // ------------------------------------------------------------- transport

    @Synchronized
    fun play() {
        if (tracks.isEmpty()) return
        if (active) {
            paused = false
        } else {
            stopRequested = false
            paused = false
            pendingIndex = -1
            seekUs = -1
            error = false
            active = true
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
        positionMs = 0
        sink.onMusicState()
    }

    fun toggle() = if (playing) pause() else play()

    /** Plays track [i] from the start. */
    @Synchronized
    fun playAt(i: Int) {
        if (i !in tracks.indices) return
        if (active) {
            pendingIndex = i
            paused = false
        } else {
            index = i
            play()
        }
        sink.onMusicState()
    }

    fun next() = playAt(nextIndex())

    /** Like most players: restarts the track if we are more than 3 s in. */
    fun previous() {
        if (active && positionMs > 3000) seekTo(0) else playAt(Math.floorMod(index - 1, maxOf(1, tracks.size)))
    }

    fun seekTo(ms: Long) {
        if (!active) return
        seekUs = ms.coerceAtLeast(0) * 1000
    }

    private fun nextIndex(): Int {
        val n = tracks.size
        if (n <= 1) return 0
        if (!shuffle) return (index + 1) % n
        var r: Int
        do r = Random.nextInt(n) while (r == index)
        return r
    }

    // ------------------------------------------------------------- player thread

    private val packet = ShortArray(Protocol.MUSIC_SAMPLES)
    private var fill = 0
    private var deadline = 0L
    private var emittedFrames = 0L
    private var basePositionUs = 0L

    /** Queues one output frame; returns false when the current track must stop. */
    private fun emit(l: Float, r: Float): Boolean {
        packet[fill++] = Pcm.clampToShort(l)
        packet[fill++] = Pcm.clampToShort(r)
        emittedFrames++
        if (fill < packet.size) return true
        fill = 0
        while (paused && !stopRequested && pendingIndex < 0 && seekUs < 0) {
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                return false
            }
            deadline = System.nanoTime()
        }
        deadline += PERIOD_NS
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
        sink.onMusicPacket(packet.copyOf())
        positionMs = (basePositionUs + emittedFrames * 1_000_000L / Protocol.MUSIC_RATE) / 1000
        return !stopRequested && pendingIndex < 0
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var failures = 0
        deadline = System.nanoTime()
        while (!stopRequested) {
            val track = tracks.getOrNull(index) ?: break
            positionMs = 0
            durationMs = 0
            sink.onMusicState()
            val ok = try {
                decode(track.uri)
            } catch (e: Exception) {
                Log.w(TAG, "decode failed: ${track.uri}", e)
                false
            }
            if (stopRequested) break
            val jump = pendingIndex
            pendingIndex = -1
            if (!ok && jump < 0) {
                if (++failures >= tracks.size) {
                    error = true
                    break
                }
            } else {
                failures = 0
            }
            if (tracks.isEmpty()) break
            index = if (jump >= 0) jump.coerceIn(0, tracks.size - 1) else nextIndex()
        }
        active = false
        paused = false
        sink.onMusicState()
    }

    /** Plays [uri] to the end. Returns false if it could not be decoded. */
    private fun decode(uri: Uri): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val trackIndex = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            ex.selectTrack(trackIndex)
            val fmt = ex.getTrackFormat(trackIndex)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            sink.onMusicState()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var resampler = StreamResampler(rate, Protocol.MUSIC_RATE, 2)
            var lpL = LowPass4(rate, cutoff(rate))
            var lpR = LowPass4(rate, cutoff(rate))
            var stereo = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            emittedFrames = 0
            basePositionUs = 0

            while (!stopRequested && pendingIndex < 0) {
                val target = seekUs
                if (target >= 0) {
                    seekUs = -1
                    ex.seekTo(target, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    c.flush()
                    inputDone = false
                    resampler.reset()
                    lpL.reset(); lpR.reset()
                    basePositionUs = maxOf(0L, ex.sampleTime)
                    emittedFrames = 0
                }
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
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = StreamResampler(rate, Protocol.MUSIC_RATE, 2)
                    lpL = LowPass4(rate, cutoff(rate))
                    lpR = LowPass4(rate, cutoff(rate))
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
                    if (seekUs >= 0) break // drop the rest of this buffer
                }
                if (eos && seekUs < 0) return true
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

    private fun cutoff(rate: Int) = minOf(14500.0, rate * 0.45)

    fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (_: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    private companion object {
        const val TAG = "MusicPlayer"
        const val PERIOD_NS = 10_000_000L
    }
}
