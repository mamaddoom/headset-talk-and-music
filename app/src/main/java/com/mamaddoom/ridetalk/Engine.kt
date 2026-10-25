package com.mamaddoom.ridetalk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.mamaddoom.ridetalk.audio.AudioRouter
import com.mamaddoom.ridetalk.audio.JitterBuffer
import com.mamaddoom.ridetalk.audio.Mixer
import com.mamaddoom.ridetalk.audio.MusicPlayer
import com.mamaddoom.ridetalk.audio.PeerAudio
import com.mamaddoom.ridetalk.audio.VoiceCapture
import com.mamaddoom.ridetalk.audio.VoiceEncoder
import com.mamaddoom.ridetalk.net.Protocol
import com.mamaddoom.ridetalk.net.Transport
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Glues the network layer and the audio engine together. Owned by
 * [IntercomService]; the UI only reads [state] and calls the control methods.
 */
class Engine(
    private val context: Context,
    private val onStateChanged: () -> Unit,
) : Transport.Listener, VoiceCapture.Sink, MusicPlayer.Sink {

    class Peer(val id: Int, @Volatile var address: InetSocketAddress) {
        val audio = PeerAudio()
        @Volatile var name = "…"
        @Volatile var lastSeenMs = System.currentTimeMillis()
        @Volatile var musicTitle = ""
        @Volatile var musicPlaying = false
    }

    data class PeerInfo(val name: String, val ip: String, val talking: Boolean, val quality: Int)

    data class State(
        val running: Boolean,
        val route: String,
        val bluetooth: Boolean,
        val headsetMic: Boolean,
        val micOk: Boolean,
        val peers: List<PeerInfo>,
        val micLevelDb: Float,
        val transmitting: Boolean,
        val muted: Boolean,
        val ptt: Boolean,
        val pttPressed: Boolean,
        val myIps: List<String>,
        val wifiBound: Boolean,
        val musicTitle: String,
        val musicPlaying: Boolean,
        val musicIsLocal: Boolean,
        val musicFrom: String,
        val playlistSize: Int,
        val musicError: Boolean,
    )

    val settings = Settings(context)
    private val myId = Random.nextInt(1, Int.MAX_VALUE)
    private val transport = Transport(context, myId, this)
    private val router = AudioRouter(context) { restartAudioSoon() }
    val music = MusicPlayer(context, this)

    private val peers = ConcurrentHashMap<Int, Peer>()
    /** Snapshot read by the mixer every 20 ms; rebuilt only when peers change. */
    @Volatile private var peerAudioList: List<PeerAudio> = emptyList()
    private val peerAudios = { peerAudioList }
    private val musicBuffer = JitterBuffer<ShortArray>(
        minTarget = musicTarget(), maxTarget = 60, capacity = 128, shrinkAfter = 3000,
    )
    @Volatile private var musicSource = 0
    @Volatile private var musicSourceAtMs = 0L

    @Volatile private var capture: VoiceCapture? = null
    @Volatile private var mixer: Mixer? = null
    private val encoder = VoiceEncoder()
    private val opusOut = ByteArray(400)
    private val voiceBuf = ByteBuffer.allocate(Protocol.MAX_PACKET)
    private val musicBuf = ByteBuffer.allocate(Protocol.MAX_PACKET)
    private val beaconBuf = ByteBuffer.allocate(Protocol.MAX_PACKET)
    private var voiceSeq = 0
    private var beaconSeq = 0

    private val ctlThread = HandlerThread("rt-ctl").apply { start() }
    private val ctl = Handler(ctlThread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false
        private set
    @Volatile var muted = false
        private set
    @Volatile var pttPressed = false
        private set
    @Volatile private var transmitting = false
    @Volatile private var micLevel = -120f
    @Volatile private var myIps: List<String> = emptyList()

    // ---------------------------------------------------------------- lifecycle

    // All audio/network lifecycle runs on the control thread so start, stop and
    // device-change restarts can never interleave.

    fun start() = onCtl {
        if (running) return@onCtl
        running = true
        transport.start()
        startAudio()
        ctl.post(tick)
        notifyState()
    }

    fun stop() {
        music.stop()
        onCtlBlocking {
            if (!running) return@onCtlBlocking
            running = false
            ctl.removeCallbacksAndMessages(null)
            stopAudio()
            transport.stop()
            peers.clear()
            updatePeerAddresses()
            musicBuffer.clear()
            notifyState()
        }
    }

    private inline fun onCtl(crossinline block: () -> Unit) {
        ctl.post { block() }
    }

    private fun onCtlBlocking(block: () -> Unit) {
        if (Looper.myLooper() == ctl.looper) {
            block()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = ctl.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun release() {
        stop()
        ctlThread.quitSafely()
    }

    private fun startAudio() {
        val sco = settings.headsetMic
        router.start(sco)
        // Give the SCO link time to come up before opening the mic, otherwise some
        // phones keep recording from the built-in microphone.
        ctl.postDelayed(openStreams, if (sco) 700L else 0L)
    }

    private val openStreams = Runnable { openStreamsNow() }

    private fun openStreamsNow() {
        if (!running) return
        closeStreams()
        val sco = settings.headsetMic
        mixer = Mixer(sco, router.outputDevice, peerAudios, musicBuffer).also {
            applyMixerSettings(it)
            it.start()
        }
        if (sco && Permissions.hasMic(context)) {
            capture = VoiceCapture(router.inputDevice, this).also {
                applyCaptureSettings(it)
                it.start()
            }
        }
        notifyState()
    }

    private fun closeStreams() {
        capture?.stop()
        capture = null
        mixer?.stop()
        mixer = null
        transmitting = false
    }

    private fun stopAudio() {
        ctl.removeCallbacks(openStreams)
        closeStreams()
        router.stop()
    }

    /** Headset connected/disconnected or mode switched: rebuild the audio path. */
    private fun restartAudioSoon() {
        if (!running) return
        ctl.post {
            closeStreams()
            router.stop()
            startAudio()
        }
    }

    // ---------------------------------------------------------------- controls

    fun setMuted(v: Boolean) {
        muted = v
        capture?.muted = v
        notifyState()
    }

    fun setPtt(pressed: Boolean) {
        pttPressed = pressed
        capture?.pttPressed = pressed
        notifyState()
    }

    /** Headset button / notification: toggles talk in PTT mode, mute in VOX mode. */
    fun headsetButton() {
        if (settings.ptt) setPtt(!pttPressed) else setMuted(!muted)
    }

    fun setPttMode(ptt: Boolean) {
        settings.ptt = ptt
        setPtt(false)
        capture?.let { applyCaptureSettings(it) }
    }

    fun setHeadsetMic(enabled: Boolean) {
        if (settings.headsetMic == enabled) return
        settings.headsetMic = enabled
        restartAudioSoon()
    }

    fun applySettings() {
        capture?.let { applyCaptureSettings(it) }
        mixer?.let { applyMixerSettings(it) }
        musicBuffer.minTarget = musicTarget()
    }

    fun setPlaylist(uris: List<Uri>) {
        settings.playlist = uris
        music.setPlaylist(uris)
    }

    /** True if another rider's music currently owns the shared channel. */
    fun remoteMusicActive(): Boolean =
        musicSource != 0 && musicSource != myId && System.currentTimeMillis() - musicSourceAtMs < 1500

    private fun applyCaptureSettings(c: VoiceCapture) {
        c.mode = if (settings.ptt) VoiceCapture.Mode.PTT else VoiceCapture.Mode.VOX
        c.voxThresholdDb = settings.voxThresholdDb()
        c.gain = settings.micGain / 100f
        c.muted = muted
        c.pttPressed = pttPressed
    }

    private fun applyMixerSettings(m: Mixer) {
        m.voiceVolume = settings.voiceVolume / 100f
        m.musicVolume = settings.musicVolume / 100f
        m.duckingEnabled = settings.ducking
    }

    /** 100 ms base buffer plus the user's sync offset, in 10 ms packets. */
    private fun musicTarget() = 10 + settings.syncDelayMs / 10

    // ---------------------------------------------------------------- periodic

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sendBeacon()
            prunePeers()
            myIps = Transport.localAddresses()
            notifyState()
            ctl.postDelayed(this, 1000)
        }
    }

    private fun sendBeacon() {
        var flags = 0
        if (transmitting) flags = flags or Protocol.FLAG_TALKING
        if (music.playing) flags = flags or Protocol.FLAG_MUSIC
        val title = if (music.playing) music.title else ""
        val len = Protocol.encodeBeacon(beaconBuf, myId, beaconSeq++, Protocol.Beacon(flags, settings.name, title))
        transport.sendBeacon(beaconBuf.array(), len)
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        for (p in peers.values) {
            if (now - p.lastSeenMs > PEER_TIMEOUT_MS) {
                peers.remove(p.id)
                changed = true
            }
        }
        if (changed) updatePeerAddresses()
    }

    @Synchronized
    private fun updatePeerAddresses() {
        val list = peers.values.toList()
        transport.peerAddresses = list.map { it.address }
        peerAudioList = list.map { it.audio }
    }

    // ---------------------------------------------------------------- network in

    override fun onPacket(header: Protocol.Header, from: InetSocketAddress, payload: ByteBuffer) {
        if (header.type == Protocol.TYPE_BYE) {
            if (peers.remove(header.sender) != null) {
                updatePeerAddresses()
                notifyState()
            }
            return
        }
        val peer = touchPeer(header.sender, from)
        when (header.type) {
            Protocol.TYPE_VOICE -> Protocol.decodeBytes(payload)?.let { peer.audio.voice.put(header.seq, it) }
            Protocol.TYPE_MUSIC -> Protocol.decodePcm(payload, Protocol.MUSIC_SAMPLES)?.let {
                acceptMusic(header.sender, header.seq, it)
            }
            Protocol.TYPE_BEACON -> Protocol.decodeBeacon(payload)?.let {
                peer.name = it.name
                peer.musicTitle = it.title
                peer.musicPlaying = it.flags and Protocol.FLAG_MUSIC != 0
            }
        }
    }

    private fun touchPeer(id: Int, from: InetSocketAddress): Peer {
        val now = System.currentTimeMillis()
        val existing = peers[id]
        if (existing != null) {
            existing.lastSeenMs = now
            if (existing.address != from) {
                existing.address = from
                updatePeerAddresses()
            }
            return existing
        }
        val p = Peer(id, from)
        peers[id] = p
        updatePeerAddresses()
        // Answer right away so the other side learns about us without waiting a second.
        ctl.post(::sendBeacon)
        notifyState()
        return p
    }

    /** One music source at a time: the first sender keeps the channel until it goes quiet. */
    private fun acceptMusic(sender: Int, seq: Int, pcm: ShortArray) {
        val now = System.currentTimeMillis()
        if (sender != musicSource) {
            if (now - musicSourceAtMs < 1500) return
            musicSource = sender
            musicBuffer.clear()
        }
        musicSourceAtMs = now
        musicBuffer.put(seq, pcm)
    }

    // ---------------------------------------------------------------- audio out

    override fun onVoiceFrame(pcm: ShortArray) {
        val n = try {
            encoder.encode(pcm, opusOut)
        } catch (_: Exception) {
            return
        }
        if (n <= 0) return
        val len = Protocol.encodeBytes(voiceBuf, Protocol.TYPE_VOICE, myId, voiceSeq++, opusOut, n)
        transport.sendToPeers(voiceBuf.array(), len)
    }

    override fun onMicLevel(levelDb: Float, transmitting: Boolean) {
        micLevel = levelDb
        val changed = this.transmitting != transmitting
        this.transmitting = transmitting
        mixer?.localTalking = transmitting
        if (changed) notifyState()
    }

    override fun onMusicPacket(seq: Int, pcm: ShortArray) {
        val len = Protocol.encodePcm(musicBuf, Protocol.TYPE_MUSIC, myId, seq, pcm, pcm.size)
        transport.sendToPeers(musicBuf.array(), len)
        acceptMusic(myId, seq, pcm)
    }

    override fun onMusicState() = notifyState()

    // ---------------------------------------------------------------- state out

    private var notifyPending = false

    /** Coalesces state updates to at most ~10 per second on the main thread. */
    fun notifyState() {
        synchronized(this) {
            if (notifyPending) return
            notifyPending = true
        }
        main.postDelayed({
            synchronized(this) { notifyPending = false }
            onStateChanged()
        }, 100)
    }

    fun state(): State {
        val now = System.currentTimeMillis()
        val src = musicSource
        val remoteMusic = remoteMusicActive()
        val local = music.playing
        val fromPeer = if (remoteMusic) peers[src] else null
        return State(
            running = running,
            route = router.routeName,
            bluetooth = router.isBluetooth,
            headsetMic = settings.headsetMic,
            micOk = capture?.ok ?: (!settings.headsetMic || Permissions.hasMic(context)),
            peers = peers.values.map {
                PeerInfo(
                    name = it.name,
                    ip = it.address.address.hostAddress.orEmpty(),
                    talking = now - it.audio.lastVoiceAtMs < 400,
                    quality = (it.audio.voice.quality() * 100).toInt(),
                )
            }.sortedBy { it.name },
            micLevelDb = micLevel,
            transmitting = transmitting,
            muted = muted,
            ptt = settings.ptt,
            pttPressed = pttPressed,
            myIps = myIps,
            wifiBound = transport.boundToWifi,
            musicTitle = when {
                local -> music.title
                fromPeer != null -> fromPeer.musicTitle
                else -> music.title
            },
            musicPlaying = local || remoteMusic,
            musicIsLocal = !remoteMusic,
            musicFrom = fromPeer?.name.orEmpty(),
            playlistSize = music.playlist.size,
            musicError = music.error,
        )
    }

    // Last, so every field above is initialised before the player calls back.
    init {
        music.setPlaylist(settings.playlist)
    }

    private companion object {
        const val PEER_TIMEOUT_MS = 5000L
    }
}
