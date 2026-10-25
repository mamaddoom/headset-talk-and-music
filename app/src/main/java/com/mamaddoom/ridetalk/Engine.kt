package com.mamaddoom.ridetalk

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.mamaddoom.ridetalk.audio.AppAudioCapture
import com.mamaddoom.ridetalk.audio.AudioRouter
import com.mamaddoom.ridetalk.audio.JitterBuffer
import com.mamaddoom.ridetalk.audio.Mixer
import com.mamaddoom.ridetalk.audio.MusicPlayer
import com.mamaddoom.ridetalk.audio.PeerAudio
import com.mamaddoom.ridetalk.audio.VoiceCapture
import com.mamaddoom.ridetalk.audio.VoiceEncoder
import com.mamaddoom.ridetalk.net.PacketCrypto
import com.mamaddoom.ridetalk.net.Protocol
import com.mamaddoom.ridetalk.net.Transport
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
        /** Music is audible on the shared channel (from us or from a peer). */
        val musicPlaying: Boolean,
        val musicIsLocal: Boolean,
        val musicFrom: String,
        val musicError: Boolean,
        val tracks: List<String>,
        val trackIndex: Int,
        val filePlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val shuffle: Boolean,
        val appAudio: Boolean,
        val appAudioSignal: Boolean,
        val appAudioFailed: Boolean,
        /** Battery saver: audio is off until someone connects. */
        val standby: Boolean,
        /** Music-quality mode: headset stays on A2DP, the mic opens on tap. */
        val hifi: Boolean,
        val talkSession: Boolean,
        /** The mic is live in the current talk session. */
        val talkReady: Boolean,
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
    /** One counter for every music source we own, so receivers see a continuous stream. */
    private val musicSeq = AtomicInteger()
    @Volatile private var appCapture: AppAudioCapture? = null
    @Volatile private var projection: MediaProjection? = null
    private var savedPlaylist: List<Uri> = emptyList()
    private var savedIndex = -1
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

    /** What the audio path is currently set up for. Only touched on the control thread. */
    private enum class AudioMode { OFF, HIFI, CALL }
    @Volatile private var audioMode = AudioMode.OFF
    /** Music-quality mode: the mic is open for one "talk session" at a time. */
    @Volatile var talkSession = false
        private set
    @Volatile private var talkStartedMs = 0L
    @Volatile private var lastSpeechMs = 0L
    @Volatile private var talkEndPosted = false
    @Volatile private var lastPeerMs = 0L
    private var startedMs = 0L

    /** A join/leave cue that arrived while the mixer was not running yet (standby wake-up). */
    @Volatile private var queuedCue: Mixer.Cue? = null
    @Volatile private var queuedCueAtMs = 0L

    /** Called (on the control thread) when battery saver decides to switch the intercom off. */
    @Volatile var onAutoStop: (() -> Unit)? = null

    // ---------------------------------------------------------------- lifecycle

    // All audio/network lifecycle runs on the control thread so start, stop and
    // device-change restarts can never interleave.

    fun start() = onCtl {
        if (running) return@onCtl
        running = true
        startedMs = System.currentTimeMillis()
        lastPeerMs = 0
        transport.crypto = groupCrypto()
        transport.start()
        reconcileAudio()
        ctl.post(tick)
        notifyState()
    }

    fun stop() {
        music.stop()
        stopAppAudio()
        onCtlBlocking {
            if (!running) return@onCtlBlocking
            running = false
            ctl.removeCallbacksAndMessages(null)
            talkSession = false
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

    /**
     * Brings the audio path in line with what is needed right now. Runs on the
     * control thread after anything that could change the answer.
     *
     * - OFF (battery saver): nobody connected and nothing playing; no mic, no
     *   Bluetooth call link, no audio threads.
     * - HIFI: headset on A2DP stereo; we hear voice and music in full quality.
     * - CALL: Bluetooth SCO with the headset mic (always in call mode, or
     *   during a tap-to-talk session in music-quality mode).
     */
    private fun reconcileAudio() {
        if (!running) return
        val want = desiredAudioMode()
        if (want == audioMode) return
        closeStreams()
        if (audioMode != AudioMode.OFF) router.stop()
        audioMode = want
        if (want != AudioMode.OFF) {
            val sco = want == AudioMode.CALL
            router.start(sco)
            // Give the SCO link time to come up before opening the mic, otherwise some
            // phones keep recording from the built-in microphone.
            ctl.postDelayed(openStreams, if (sco) 700L else 0L)
        }
        notifyState()
    }

    private fun desiredAudioMode(): AudioMode {
        val needed = !settings.batterySaver || peers.isNotEmpty() || music.active || appCapture != null ||
            talkSession || System.currentTimeMillis() - lastPeerMs < STANDBY_DELAY_MS
        return when {
            !needed -> AudioMode.OFF
            settings.headsetMic || talkSession -> AudioMode.CALL
            else -> AudioMode.HIFI
        }
    }

    private val reconcile = Runnable { reconcileAudio() }

    private fun reconcileSoon() {
        ctl.removeCallbacks(reconcile)
        ctl.post(reconcile)
    }

    private val openStreams = Runnable { openStreamsNow() }

    private fun openStreamsNow() {
        if (!running || audioMode == AudioMode.OFF) return
        closeStreams()
        val sco = audioMode == AudioMode.CALL
        val m = Mixer(sco, router.outputDevice, peerAudios, musicBuffer).also {
            applyMixerSettings(it)
            it.start()
        }
        mixer = m
        queuedCue?.let {
            if (System.currentTimeMillis() - queuedCueAtMs < 3000) m.cue(it)
            queuedCue = null
        }
        if (sco && Permissions.hasMic(context)) {
            capture = VoiceCapture(router.inputDevice, this).also {
                applyCaptureSettings(it)
                if (talkSession) {
                    // Tap-to-talk: voice-activated, held open briefly so the first words get through.
                    it.mode = VoiceCapture.Mode.VOX
                    it.muted = false
                    it.holdOpenFrames = TALK_HOLD_FRAMES
                    talkStartedMs = System.currentTimeMillis()
                    lastSpeechMs = talkStartedMs
                    m.cue(Mixer.Cue.READY)
                }
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
        if (audioMode != AudioMode.OFF) router.stop()
        audioMode = AudioMode.OFF
    }

    /** Headset connected/disconnected: rebuild the audio path from scratch. */
    private fun restartAudioSoon() {
        if (!running) return
        ctl.post {
            stopAudio()
            reconcileAudio()
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

    /**
     * Headset button / notification. Music-quality mode: start or end a talk
     * session. Call mode: toggles talk in push-to-talk, mute in open-mic.
     */
    fun headsetButton() {
        when {
            !settings.headsetMic -> toggleTalk()
            settings.ptt -> setPtt(!pttPressed)
            else -> setMuted(!muted)
        }
    }

    /** Music-quality mode: opens the headset mic until you stop talking (or tap again). */
    fun toggleTalk() = onCtl {
        if (!running || settings.headsetMic) return@onCtl
        if (talkSession) endTalk() else if (Permissions.hasMic(context)) startTalk()
    }

    private fun startTalk() {
        talkSession = true
        talkEndPosted = false
        talkStartedMs = System.currentTimeMillis()
        lastSpeechMs = talkStartedMs
        reconcileAudio()
        notifyState()
    }

    private fun endTalk() {
        talkSession = false
        talkEndPosted = false
        reconcileAudio()
        notifyState()
    }

    fun setPttMode(ptt: Boolean) {
        settings.ptt = ptt
        setPtt(false)
        capture?.let { applyCaptureSettings(it) }
    }

    fun setHeadsetMic(enabled: Boolean) {
        if (settings.headsetMic == enabled) return
        settings.headsetMic = enabled
        onCtl {
            talkSession = false
            reconcileAudio()
        }
    }

    /** Changes the group code; riders with a different code disappear immediately. */
    fun setGroupCode(code: String) {
        settings.groupCode = code
        onCtl {
            transport.crypto = groupCrypto()
            peers.clear()
            updatePeerAddresses()
            notifyState()
        }
    }

    private fun groupCrypto(): PacketCrypto? =
        settings.groupCode.takeIf { it.isNotBlank() }?.let { PacketCrypto(it) }

    private fun playCue(cue: Mixer.Cue) {
        if (!settings.cues) return
        val m = mixer
        if (m != null) {
            m.cue(cue)
        } else {
            queuedCue = cue
            queuedCueAtMs = System.currentTimeMillis()
        }
    }

    fun setBatterySaver(enabled: Boolean) {
        settings.batterySaver = enabled
        reconcileSoon()
    }

    fun applySettings() {
        capture?.let { applyCaptureSettings(it) }
        mixer?.let { applyMixerSettings(it) }
        musicBuffer.minTarget = musicTarget()
    }

    // ---------------------------------------------------------------- music

    val appAudioActive get() = appCapture != null

    /** Play/pause the local playlist; takes the channel back from app-audio capture. */
    fun toggleMusic() {
        if (!music.playing) stopAppAudio()
        music.toggle()
    }

    fun playTrack(i: Int) {
        stopAppAudio()
        music.playAt(i)
    }

    /** Starts streaming whatever other apps play. The engine owns [p] from now on. */
    fun startAppAudio(p: MediaProjection) {
        music.pause()
        stopAppAudio()
        if (Build.VERSION.SDK_INT < 29) {
            p.stop()
            return
        }
        projection = p
        appCapture = AppAudioCapture(p) { sendMusic(it, playLocally = false) }.also { it.start() }
        reconcileSoon()
        notifyState()
    }

    fun stopAppAudio() {
        val c = appCapture ?: return
        appCapture = null
        c.stop()
        projection?.let {
            projection = null
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        notifyState()
    }

    /** True if another rider's music currently owns the shared channel. */
    fun remoteMusicActive(): Boolean =
        musicSource != 0 && musicSource != myId && System.currentTimeMillis() - musicSourceAtMs < 1500

    private fun applyCaptureSettings(c: VoiceCapture) {
        // A tap-to-talk session is always voice-activated and never muted.
        c.mode = if (settings.ptt && !talkSession) VoiceCapture.Mode.PTT else VoiceCapture.Mode.VOX
        c.voxThresholdDb = settings.voxThresholdDb()
        c.gain = settings.micGain / 100f
        c.muted = muted && !talkSession
        c.pttPressed = pttPressed
        c.noiseReduction = settings.noiseReduction
        c.antiFeedback = settings.antiFeedback
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
            checkTalkTimeout()
            reconcileAudio()
            checkAutoStop()
            notifyState()
            ctl.postDelayed(this, 1000)
        }
    }

    private fun sendBeacon() {
        var flags = 0
        if (transmitting) flags = flags or Protocol.FLAG_TALKING
        val appSignal = appCapture?.hasSignal == true
        if (music.playing || appSignal) flags = flags or Protocol.FLAG_MUSIC
        val title = when {
            music.playing -> music.title
            appSignal -> context.getString(R.string.app_audio_title)
            else -> ""
        }
        val len = Protocol.encodeBeacon(beaconBuf, myId, beaconSeq++, Protocol.Beacon(flags, settings.name, title))
        transport.sendBeacon(beaconBuf.array(), len)
    }

    /** Ends a talk session whose mic never delivered audio (capture failed or no permission). */
    private fun checkTalkTimeout() {
        if (!talkSession) return
        val now = System.currentTimeMillis()
        if (now - lastSpeechMs > TALK_IDLE_MS + 1000 && now - talkStartedMs > TALK_MIN_MS + 1000) endTalk()
    }

    /** Battery saver: switch off after a long time alone with nothing playing. */
    private fun checkAutoStop() {
        if (!settings.batterySaver || peers.isNotEmpty() || music.active || appCapture != null) return
        val now = System.currentTimeMillis()
        if (now - maxOf(lastPeerMs, startedMs) > AUTO_STOP_MS) onAutoStop?.invoke()
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        if (peers.isNotEmpty()) lastPeerMs = now
        var changed = false
        for (p in peers.values) {
            if (now - p.lastSeenMs > PEER_TIMEOUT_MS) {
                peers.remove(p.id)
                changed = true
            }
        }
        if (changed) playCue(Mixer.Cue.LEAVE)
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
                playCue(Mixer.Cue.LEAVE)
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
        lastPeerMs = now
        updatePeerAddresses()
        playCue(Mixer.Cue.JOIN)
        reconcileSoon() // wake from battery-saver standby
        // Answer right away so the other side learns about us without waiting a second.
        ctl.post(::sendBeacon)
        notifyState()
        return p
    }

    /** One music source at a time: the first sender keeps the channel until it goes quiet. */
    private fun acceptMusic(sender: Int, seq: Int, pcm: ShortArray) {
        if (!claimMusic(sender)) return
        musicBuffer.put(seq, pcm)
    }

    /** Returns false if another source owns the channel; clears the buffer on a change of source. */
    @Synchronized
    private fun claimMusic(sender: Int): Boolean {
        val now = System.currentTimeMillis()
        if (sender != musicSource) {
            if (now - musicSourceAtMs < 1500) return false
            musicSource = sender
            musicBuffer.clear()
        }
        musicSourceAtMs = now
        return true
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
        if (talkSession) {
            val now = System.currentTimeMillis()
            if (transmitting) {
                lastSpeechMs = now
            } else if (!talkEndPosted && now - lastSpeechMs > TALK_IDLE_MS && now - talkStartedMs > TALK_MIN_MS) {
                // You stopped talking: go back to full-quality music.
                talkEndPosted = true
                ctl.post { if (talkSession) endTalk() }
            }
        }
        val changed = this.transmitting != transmitting
        this.transmitting = transmitting
        mixer?.localTalking = transmitting
        if (changed) notifyState()
    }

    override fun isFarEndActive() = mixer?.remoteTalking == true

    override fun onMusicPacket(pcm: ShortArray) = sendMusic(pcm, playLocally = true)

    /**
     * Sends one music packet to the peers. Playlist music is also played here,
     * through the same buffer the peers use, so everyone hears it in sync.
     * Captured app audio is not: this phone already hears the original app.
     */
    private fun sendMusic(pcm: ShortArray, playLocally: Boolean) {
        val seq = musicSeq.getAndIncrement()
        synchronized(musicBuf) {
            val len = Protocol.encodePcm(musicBuf, Protocol.TYPE_MUSIC, myId, seq, pcm, pcm.size)
            transport.sendToPeers(musicBuf.array(), len)
        }
        if (playLocally) {
            acceptMusic(myId, seq, pcm)
        } else if (claimMusic(myId)) {
            musicBuffer.clear() // keep nothing of ours queued locally
        }
    }

    override fun onMusicState() {
        // Persist playlist edits and the current track (cheap; only writes on change).
        val uris = music.tracks.map { it.uri }
        synchronized(this) {
            if (uris != savedPlaylist || music.index != savedIndex) {
                savedPlaylist = uris
                savedIndex = music.index
                settings.playlist = uris
                settings.trackIndex = music.index
            }
        }
        reconcileSoon() // playing music wakes the audio path from standby
        notifyState()
    }

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
        val cap = appCapture
        val local = music.playing || cap?.hasSignal == true
        val fromPeer = if (remoteMusic) peers[src] else null
        return State(
            running = running,
            route = router.routeName,
            bluetooth = router.isBluetooth,
            headsetMic = settings.headsetMic,
            micOk = capture?.ok ?: Permissions.hasMic(context),
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
                fromPeer != null -> fromPeer.musicTitle
                cap != null -> context.getString(R.string.app_audio_title)
                else -> music.title
            },
            musicPlaying = local || remoteMusic,
            musicIsLocal = !remoteMusic,
            musicFrom = fromPeer?.name.orEmpty(),
            musicError = music.error,
            tracks = music.tracks.map { it.title },
            trackIndex = music.index,
            filePlaying = music.playing,
            positionMs = music.positionMs,
            durationMs = music.durationMs,
            shuffle = music.shuffle,
            appAudio = cap != null,
            appAudioSignal = cap?.hasSignal == true,
            appAudioFailed = cap?.failed == true,
            standby = running && audioMode == AudioMode.OFF,
            hifi = !settings.headsetMic,
            talkSession = talkSession,
            talkReady = talkSession && capture != null && audioMode == AudioMode.CALL,
        )
    }

    // Last, so every field above is initialised before the player calls back.
    init {
        music.shuffle = settings.shuffle
        music.setTracks(settings.playlist, settings.trackIndex)
    }

    private companion object {
        const val PEER_TIMEOUT_MS = 5000L
        /** Keep audio up this long after the last rider drops, to ride out short gaps. */
        const val STANDBY_DELAY_MS = 20_000L
        const val AUTO_STOP_MS = 30 * 60_000L
        const val TALK_HOLD_FRAMES = 150 // 3 s
        const val TALK_IDLE_MS = 2500L
        const val TALK_MIN_MS = 4000L
    }
}
