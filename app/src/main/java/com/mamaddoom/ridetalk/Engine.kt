package com.mamaddoom.ridetalk

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.mamaddoom.ridetalk.audio.Announcer
import com.mamaddoom.ridetalk.audio.AppAudioCapture
import com.mamaddoom.ridetalk.audio.AudioRouter
import com.mamaddoom.ridetalk.audio.JitterBuffer
import com.mamaddoom.ridetalk.audio.Mixer
import com.mamaddoom.ridetalk.audio.MusicEncoder
import com.mamaddoom.ridetalk.audio.MusicPlayer
import com.mamaddoom.ridetalk.audio.MusicRateControl
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
        /** Two-phone setup roles announced in the beacon. */
        @Volatile var talkPhone = false
        @Volatile var musicPhone = false
        /** Music loss this rider reported in its last beacon (percent, or LOSS_UNKNOWN). */
        @Volatile var musicLoss = Protocol.LOSS_UNKNOWN
        @Volatile var musicLossAtMs = 0L
        @Volatile var onCall = false
        /** Name known from a beacon (the first packet may arrive before it). */
        @Volatile var named = false
    }

    data class PeerInfo(
        val name: String,
        val ip: String,
        val talking: Boolean,
        val quality: Int,
        val talkPhone: Boolean,
        val musicPhone: Boolean,
        /** The other phone of this same rider (two-phone setup). */
        val mine: Boolean,
        /** Not heard for a few seconds: link fading, waiting for it to come back. */
        val weak: Boolean,
        /** On a phone call: their intercom is paused. */
        val onCall: Boolean,
    )

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
        /** Current audio link carries full-quality stereo (not a classic Bluetooth call link). */
        val linkHighQuality: Boolean,
        val leAudio: Boolean,
        // Trip setup checklist
        val role: Settings.Role,
        val headsetConnected: Boolean,
        val otherPhoneConnected: Boolean,
        /** Seconds left in a mic test, 0 when none is running. */
        val micTestLeftS: Int,
        /** Wi-Fi signal (dBm) and band when this phone is a hotspot client; null on the hotspot. */
        val wifiRssi: Int?,
        val wifiMhz: Int?,
        /** This phone is on a call: intercom paused until it ends. */
        val onCall: Boolean,
        /** Bitrate the shared music is sent at now (kbit/s), 0 when we send none. */
        val musicKbps: Int,
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
    private val musicBuffer = JitterBuffer<ByteArray>(
        minTarget = musicTarget(), maxTarget = 40, capacity = 64, periodMs = 20.0,
    )
    private val musicEncoder = MusicEncoder()
    /** Lowers the music bitrate when listeners report loss (weak link), raises it again when clean. */
    private val musicRate = MusicRateControl()
    @Volatile private var musicRedundancy = Protocol.MUSIC_REDUNDANCY
    /** Our own recent loss on received music, reported to the sender in our beacon. */
    @Volatile private var rxMusicLoss = Protocol.LOSS_UNKNOWN
    private var lastMusicPlayed = 0L
    private var lastMusicConcealed = 0L
    /** Recently sent frames, newest first, for the redundancy containers. */
    private val voiceHistory = ArrayDeque<ByteArray>()
    private val musicHistory = ArrayDeque<ByteArray>()
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
    private val keepaliveBuf = ByteBuffer.allocate(Protocol.HEADER)
    private var keepaliveSeq = 0
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
    private enum class AudioMode {
        OFF,
        /** Headset on stereo Bluetooth, no mic. */
        HIFI,
        /** Bluetooth call link with the headset mic. */
        CALL,
    }
    @Volatile private var audioMode = AudioMode.OFF
    /** Music-quality mode: the mic is open for one "talk session" at a time. */
    @Volatile var talkSession = false
        private set
    @Volatile private var talkStartedMs = 0L
    @Volatile private var lastSpeechMs = 0L
    @Volatile private var talkEndPosted = false
    @Volatile private var lastPeerMs = 0L
    private var startedMs = 0L

    /** Mic test: your own voice is played back to you for a few seconds. */
    private val loopback = PeerAudio()
    @Volatile var micTestUntilMs = 0L
        private set
    private var loopbackSeq = 0

    /** A join/leave cue that arrived while the mixer was not running yet (standby wake-up). */
    @Volatile private var queuedCue: Mixer.Cue? = null
    @Volatile private var queuedCueAtMs = 0L

    /** A phone call (any app) is ringing or running: everything paused and private. */
    @Volatile var onCall = false
        private set
    private var callSeenMs = 0L
    private var callGoneMs = 0L
    private var resumeMusicAfterCall = false

    private var announcer: Announcer? = null
    @Volatile private var queuedSpeech: Pair<FloatArray, Int>? = null

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
        onCall = false
        reconcileAudio()
        ctl.post(tick)
        ctl.post(keepalive)
        ctl.post(callWatch)
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
        announcer?.release()
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
            talkSession || micTesting || System.currentTimeMillis() - lastPeerMs < STANDBY_DELAY_MS
        val role = settings.role
        return when {
            // A phone call owns the headset; nothing of ours may be heard or recorded.
            onCall -> AudioMode.OFF
            !needed -> AudioMode.OFF
            role == Settings.Role.MUSIC -> AudioMode.HIFI
            role == Settings.Role.TALK -> AudioMode.CALL
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
        val role = settings.role
        // Only a classic Bluetooth call link forces 16 kHz mono; LE Audio keeps full quality with the mic on.
        val m = Mixer(sco, !sco || !router.isNarrowband, router.outputDevice, peerAudios, musicBuffer).also {
            applyMixerSettings(it)
            it.start()
        }
        mixer = m
        queuedCue?.let {
            if (System.currentTimeMillis() - queuedCueAtMs < 3000) m.cue(it)
            queuedCue = null
        }
        queuedSpeech?.let {
            if (System.currentTimeMillis() - queuedCueAtMs < 3000) m.say(it.first, it.second)
            queuedSpeech = null
        }
        if (sco && role != Settings.Role.MUSIC && Permissions.hasMic(context)) {
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
            settings.role == Settings.Role.MUSIC -> toggleMusic() // music phone: play/pause
            tapToTalk -> toggleTalk()
            settings.ptt -> setPtt(!pttPressed)
            else -> setMuted(!muted)
        }
    }

    /**
     * Headset double-tap: switch what the headset does without touching the
     * phone. One phone: music quality <-> mic always on, with a distinct cue
     * for each. Talk phone: mute toggle. Music phone: next song.
     */
    fun headsetDoubleTap() {
        when (settings.role) {
            Settings.Role.NORMAL -> {
                val toCall = !settings.headsetMic
                setHeadsetMic(toCall)
                // The audio path restarts; the announcement waits for the new mixer.
                if (toCall) announce(Mixer.Cue.MODE_CALL, R.string.say_mode_call, queue = true)
                else announce(Mixer.Cue.MODE_MUSIC, R.string.say_mode_music, queue = true)
            }
            Settings.Role.TALK -> setMuted(!muted)
            Settings.Role.MUSIC -> music.next()
        }
        notifyState()
    }

    /** Runs a hands-free control mapped in settings (headset taps, volume-key combos). */
    fun perform(action: Controls.Action) {
        when (action) {
            Controls.Action.NONE -> return
            Controls.Action.TALK -> headsetButton()
            Controls.Action.SWITCH_MODE -> headsetDoubleTap()
            Controls.Action.MUTE -> {
                setMuted(!muted)
                if (muted) announce(Mixer.Cue.MUTED, R.string.say_mic_off) else announce(Mixer.Cue.UNMUTED, R.string.say_mic_on)
            }
            Controls.Action.PLAY_PAUSE -> toggleMusic()
            Controls.Action.NEXT_TRACK -> music.next()
            Controls.Action.PREV_TRACK -> music.previous()
            Controls.Action.MUSIC_UP, Controls.Action.MUSIC_DOWN -> {
                val step = if (action == Controls.Action.MUSIC_UP) MUSIC_STEP else -MUSIC_STEP
                settings.musicVolume = (settings.musicVolume + step).coerceIn(0, 100)
                applySettings()
                playCue(Mixer.Cue.TICK)
            }
        }
        notifyState()
    }

    /** Wi-Fi came back or changed: announce ourselves at once instead of waiting a second. */
    override fun onNetworkChanged() {
        ctl.postDelayed(::sendBeacon, 300)
        ctl.postDelayed(::sendBeacon, 1000)
    }

    /** Music-quality mode: opens the headset mic until you stop talking (or tap again). */
    /** Music-quality mode with the headset mic: the only setup where talking needs a tap. */
    val tapToTalk: Boolean
        get() = !settings.headsetMic && settings.role == Settings.Role.NORMAL

    fun toggleTalk() = onCtl {
        if (!running || !tapToTalk) return@onCtl
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

    private fun playCue(cue: Mixer.Cue, queue: Boolean = false) {
        if (!settings.cues) return
        val m = mixer
        if (m != null && !queue) {
            m.cue(cue)
        } else {
            queuedCue = cue
            queuedCueAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Says [phrase] (with [name] filled in) when spoken announcements are on and
     * the phone has a voice; otherwise plays [cue]. [queue]: the audio path is
     * about to restart, so wait for the new mixer.
     */
    private fun announce(cue: Mixer.Cue, phrase: Int, name: String? = null, anon: Int = phrase, queue: Boolean = false) {
        if (!settings.cues) return
        val a = announcer
        if (!settings.voicePrompts || a == null || !a.ready) {
            playCue(cue, queue)
            return
        }
        // An English voice can't read a Persian name: say it without the name.
        val readable = name != null && name.isNotBlank() && (a.persian || name.all { it.code < 0x250 })
        val text = when {
            readable -> a.res.getString(phrase, name)
            else -> a.res.getString(anon)
        }
        if (queue) {
            queuedSpeechText = text
            queuedCueAtMs = System.currentTimeMillis()
        }
        a.say(text)
    }

    /** Text that should play on the next mixer if the current one is going away. */
    @Volatile private var queuedSpeechText: String? = null

    private fun onSpeech(samples: FloatArray, rate: Int) {
        val m = mixer
        val waitingForRestart = queuedSpeechText != null && System.currentTimeMillis() - queuedCueAtMs < 1500
        queuedSpeechText = null
        if (m != null && !waitingForRestart) {
            m.say(samples, rate)
        } else {
            queuedSpeech = samples to rate
            queuedCueAtMs = System.currentTimeMillis()
        }
    }

    /** (Re)creates the voice for announcements, e.g. after the app language changed. */
    fun reloadVoice() {
        main.post {
            announcer?.release()
            announcer = Announcer(context, settings.language, FIXED_PHRASES, ::onSpeech)
        }
    }

    /** Applies a trip setup chosen on the setup card and rebuilds the audio path. */
    fun setSetup(twoPhones: Boolean, talkPhone: Boolean) {
        settings.twoPhones = twoPhones
        settings.isTalkPhone = talkPhone
        onCtl {
            talkSession = false
            stopAudio()
            reconcileAudio()
            notifyState()
        }
    }

    /**
     * Same rider name and the complementary role: the other phone of a
     * two-phone setup. Only used for the checklist and the peer list; audio
     * never depends on it (the music phone plays no voices at all, and no
     * phone ever plays its own mic), so a name typo can't cause an echo.
     */
    private fun isMyOtherPhone(p: Peer): Boolean {
        val role = settings.role
        if (role == Settings.Role.NORMAL) return false
        if (!p.name.trim().equals(settings.name.trim(), ignoreCase = true)) return false
        return if (role == Settings.Role.MUSIC) p.talkPhone else p.musicPhone
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
        c.vadSensitivity = settings.voxSensitivity
        c.gain = settings.micGain / 100f
        c.muted = muted && !talkSession
        c.pttPressed = pttPressed
        c.noiseReduction = settings.noiseReduction
        c.antiFeedback = settings.antiFeedback && !micTesting
    }

    private fun applyMixerSettings(m: Mixer) {
        m.voiceVolume = settings.voiceVolume / 100f
        m.musicVolume = settings.musicVolume / 100f
        m.duckingEnabled = settings.ducking
        m.mono = settings.monoOutput
        m.rxDenoise = settings.rxDenoise
        // The mic test plays our own voice back on purpose.
        m.echoGuard = settings.echoGuard && !micTesting
    }

    /** 100 ms base buffer plus the user's sync offset, in 20 ms packets. */
    private fun musicTarget() = 5 + settings.syncDelayMs / 20

    // ---------------------------------------------------------------- periodic

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            measureMusicLoss()
            adaptMusicRate()
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

    /** Keeps the Wi-Fi radios awake while the audio path is up but nothing else is being sent. */
    private val keepalive = object : Runnable {
        override fun run() {
            if (!running) return
            val quiet = !transmitting && !music.playing && appCapture == null
            if (audioMode != AudioMode.OFF && quiet && peers.isNotEmpty()) {
                val len = Protocol.encodeKeepalive(keepaliveBuf, myId, keepaliveSeq++)
                transport.sendToPeers(keepaliveBuf.array(), len)
            }
            ctl.postDelayed(this, KEEPALIVE_MS)
        }
    }

    /** Loss on the music we receive over the last second, for our beacon. */
    private fun measureMusicLoss() {
        val played = musicBuffer.played
        val concealed = musicBuffer.concealed
        val dp = played - lastMusicPlayed
        val dc = concealed - lastMusicConcealed
        lastMusicPlayed = played
        lastMusicConcealed = concealed
        rxMusicLoss = if (remoteMusicActive() && dp + dc >= 10) (dc * 100 / (dp + dc)).toInt()
        else Protocol.LOSS_UNKNOWN
    }

    /** While we send music: follow the worst loss any listener reports. */
    private fun adaptMusicRate() {
        val sending = music.playing || appCapture != null
        if (!sending) {
            if (musicRate.level != 0) {
                musicRate.reset()
                applyMusicRate()
            }
            return
        }
        val now = System.currentTimeMillis()
        val worst = peers.values
            .filter { now - it.musicLossAtMs < 3000 && it.musicLoss != Protocol.LOSS_UNKNOWN }
            .maxOfOrNull { it.musicLoss }
        if (musicRate.update(worst, now)) applyMusicRate()
    }

    private fun applyMusicRate() {
        val l = musicRate.current
        synchronized(musicBuf) {
            musicEncoder.bitrate = l.bitrate
            musicRedundancy = l.redundancy
        }
        notifyState()
    }

    /**
     * Phone calls: while one rings or runs (cellular or another app's call) the
     * intercom pauses completely, so the call stays private and nobody hears it;
     * it comes back by itself when the call ends. Debounced so a brief mode
     * flicker doesn't drop the audio.
     */
    private val callWatch = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (router.phoneCallActive()) {
                callGoneMs = 0
                if (callSeenMs == 0L) callSeenMs = now
                if (!onCall && now - callSeenMs >= CALL_START_MS) callStarted()
            } else {
                callSeenMs = 0
                if (callGoneMs == 0L) callGoneMs = now
                if (onCall && now - callGoneMs >= CALL_END_MS) callEnded()
            }
            ctl.postDelayed(this, 500)
        }
    }

    private fun callStarted() {
        onCall = true
        talkSession = false
        resumeMusicAfterCall = music.playing
        if (music.playing) main.post { music.pause() }
        stopAudio()
        sendBeacon()
        notifyState()
    }

    private fun callEnded() {
        onCall = false
        sendBeacon()
        if (resumeMusicAfterCall) main.post { music.play() }
        resumeMusicAfterCall = false
        reconcileAudio()
        announce(Mixer.Cue.READY, R.string.say_intercom_back, queue = true)
        notifyState()
    }

    private fun sendBeacon() {
        var flags = 0
        if (onCall) flags = flags or Protocol.FLAG_ON_CALL
        if (transmitting) flags = flags or Protocol.FLAG_TALKING
        val appSignal = appCapture?.hasSignal == true
        if (music.playing || appSignal) flags = flags or Protocol.FLAG_MUSIC
        when (settings.role) {
            Settings.Role.TALK -> flags = flags or Protocol.FLAG_TALK_PHONE
            Settings.Role.MUSIC -> flags = flags or Protocol.FLAG_MUSIC_PHONE
            Settings.Role.NORMAL -> Unit
        }
        val title = when {
            music.playing -> music.title
            appSignal -> context.getString(R.string.app_audio_title)
            else -> ""
        }
        val beacon = Protocol.Beacon(flags, settings.name, title, rxMusicLoss)
        val len = Protocol.encodeBeacon(beaconBuf, myId, beaconSeq++, beacon)
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
        val gone = peers.values.filter { now - it.lastSeenMs > PEER_TIMEOUT_MS }
        for (p in gone) peers.remove(p.id)
        if (gone.isNotEmpty()) {
            announce(Mixer.Cue.LEAVE, R.string.say_left, gone.first().name, R.string.say_left_anon)
            updatePeerAddresses()
        }
    }

    @Synchronized
    private fun updatePeerAddresses() {
        val list = peers.values.toList()
        transport.peerAddresses = list.map { it.address }
        val audios = list.map { it.audio }
        peerAudioList = if (micTesting) audios + loopback else audios
    }

    private val micTesting get() = System.currentTimeMillis() < micTestUntilMs

    /**
     * Plays your own mic back to you for [MIC_TEST_MS], on this phone only, so
     * you can check the mic when nobody else is connected. Wakes the audio path.
     */
    fun startMicTest() = onCtl {
        if (!running || settings.role == Settings.Role.MUSIC) return@onCtl
        micTestUntilMs = System.currentTimeMillis() + MIC_TEST_MS
        loopback.voice.clear()
        loopback.decoder.reset()
        capture?.antiFeedback = false // we *want* to hear ourselves now
        mixer?.echoGuard = false
        updatePeerAddresses()
        if (tapToTalk && !talkSession) startTalk() else reconcileAudio()
        ctl.postDelayed(endMicTest, MIC_TEST_MS)
        notifyState()
    }

    private val endMicTest = Runnable {
        micTestUntilMs = 0
        capture?.let { applyCaptureSettings(it) }
        mixer?.let { applyMixerSettings(it) }
        updatePeerAddresses()
        notifyState()
    }

    // ---------------------------------------------------------------- network in

    override fun onPacket(header: Protocol.Header, from: InetSocketAddress, payload: ByteBuffer) {
        if (header.type == Protocol.TYPE_BYE) {
            val p = peers.remove(header.sender)
            if (p != null) {
                updatePeerAddresses()
                announce(Mixer.Cue.LEAVE, R.string.say_left, p.name, R.string.say_left_anon)
                notifyState()
            }
            return
        }
        val peer = touchPeer(header.sender, from)
        when (header.type) {
            Protocol.TYPE_VOICE -> {
                if (settings.role == Settings.Role.MUSIC) {
                    // Music phone: voices play on the talk phone; here they only duck the music.
                    mixer?.voiceElsewhereAtMs = System.currentTimeMillis()
                } else {
                    // Newest frame first; the older ones refill holes left by lost packets.
                    Protocol.decodeFrames(payload)?.forEachIndexed { k, f ->
                        peer.audio.voice.put(header.seq - k, f, timed = k == 0)
                    }
                }
            }
            Protocol.TYPE_MUSIC -> if (settings.role != Settings.Role.TALK) {
                // The talk phone never plays music; the music phone does.
                val frames = Protocol.decodeFrames(payload)
                if (frames != null && claimMusic(header.sender)) {
                    frames.forEachIndexed { k, f -> musicBuffer.put(header.seq - k, f, timed = k == 0) }
                }
            }
            Protocol.TYPE_BEACON -> Protocol.decodeBeacon(payload)?.let {
                peer.name = it.name
                peer.named = true
                peer.musicLoss = it.musicLoss
                peer.musicLossAtMs = System.currentTimeMillis()
                val call = it.flags and Protocol.FLAG_ON_CALL != 0
                if (call != peer.onCall) {
                    peer.onCall = call
                    if (call) announce(Mixer.Cue.LEAVE, R.string.say_on_call, it.name, R.string.say_on_call_anon)
                    else announce(Mixer.Cue.JOIN, R.string.say_call_over, it.name, R.string.say_call_over_anon)
                    notifyState()
                }
                peer.musicTitle = it.title
                peer.musicPlaying = it.flags and Protocol.FLAG_MUSIC != 0
                peer.talkPhone = it.flags and Protocol.FLAG_TALK_PHONE != 0
                peer.musicPhone = it.flags and Protocol.FLAG_MUSIC_PHONE != 0
            }
        }
    }

    private fun touchPeer(id: Int, from: InetSocketAddress): Peer {
        val now = System.currentTimeMillis()
        val existing = peers[id]
        if (existing != null) {
            // Back after a long gap (tunnel, distance): let the rider know the link returned.
            if (now - existing.lastSeenMs > WEAK_MS * 3) {
                announce(Mixer.Cue.JOIN, R.string.say_back, existing.name, R.string.say_back_anon)
            }
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
        // Wait briefly for their beacon so the announcement can use their name.
        ctl.postDelayed({
            if (peers[id] === p) {
                announce(Mixer.Cue.JOIN, R.string.say_joined, p.name.takeIf { p.named }, R.string.say_joined_anon)
            }
        }, 1200)
        reconcileSoon() // wake from battery-saver standby
        // Answer right away so the other side learns about us without waiting a second.
        ctl.post(::sendBeacon)
        notifyState()
        return p
    }

    /**
     * One music source at a time: the first sender keeps the channel until it
     * goes quiet. Returns false if another source owns it; clears the buffer on
     * a change of source.
     */
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
        val frame = opusOut.copyOf(n)
        voiceHistory.addFirst(frame)
        while (voiceHistory.size > Protocol.VOICE_REDUNDANCY) voiceHistory.removeLast()
        val len = Protocol.encodeFrames(voiceBuf, Protocol.TYPE_VOICE, myId, voiceSeq++, voiceHistory)
        transport.sendToPeers(voiceBuf.array(), len)
        mixer?.localSpeechAtMs = System.currentTimeMillis()
        if (micTesting) loopback.voice.put(loopbackSeq++, frame)
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

    // Feedback loops only happen through the phone's own speaker; with headsets the
    // mic must stay fully open even while others talk.
    override fun isFarEndActive() = mixer?.remoteTalking == true && router.isLoudspeaker

    override fun onMusicPacket(pcm: ShortArray) = sendMusic(pcm, playLocally = true)

    /**
     * Sends one music packet to the peers. Playlist music is also played here,
     * through the same buffer the peers use, so everyone hears it in sync.
     * Captured app audio is not: this phone already hears the original app.
     */
    private fun sendMusic(pcm: ShortArray, playLocally: Boolean) {
        val seq: Int
        val frame: ByteArray
        synchronized(musicBuf) {
            frame = musicEncoder.encode(pcm) ?: return
            seq = musicSeq.getAndIncrement()
            musicHistory.addFirst(frame)
            while (musicHistory.size > musicRedundancy) musicHistory.removeLast()
            val len = Protocol.encodeFrames(musicBuf, Protocol.TYPE_MUSIC, myId, seq, musicHistory)
            transport.sendToPeers(musicBuf.array(), len)
        }
        if (playLocally) {
            // Our own music goes through the same buffer (and decoder) as everyone else's: in sync.
            if (claimMusic(myId)) musicBuffer.put(seq, frame)
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
                    talkPhone = it.talkPhone,
                    musicPhone = it.musicPhone,
                    mine = isMyOtherPhone(it),
                    weak = now - it.lastSeenMs > WEAK_MS,
                    onCall = it.onCall,
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
            hifi = tapToTalk,
            talkSession = talkSession,
            talkReady = talkSession && capture != null && audioMode == AudioMode.CALL,
            linkHighQuality = audioMode == AudioMode.HIFI || (audioMode == AudioMode.CALL && !router.isNarrowband),
            leAudio = audioMode != AudioMode.OFF && router.isLeAudio,
            role = settings.role,
            headsetConnected = router.bluetoothHeadsetPresent(),
            otherPhoneConnected = peers.values.any { isMyOtherPhone(it) },
            micTestLeftS = ((micTestUntilMs - now + 999) / 1000).toInt().coerceAtLeast(0),
            wifiRssi = transport.wifiRssi,
            wifiMhz = transport.wifiMhz,
            onCall = onCall,
            musicKbps = if (music.playing || cap != null) musicRate.current.bitrate / 1000 else 0,
        )
    }

    // Last, so every field above is initialised before the player calls back.
    init {
        music.shuffle = settings.shuffle
        music.setTracks(settings.playlist, settings.trackIndex)
        reloadVoice()
    }

    private companion object {
        /**
         * A rider stays in the group through this much silence (fading highway
         * link, tunnel). Only after it are they dropped; before that they show as
         * "weak signal" and come straight back when packets arrive.
         */
        const val PEER_TIMEOUT_MS = 30_000L
        const val MUSIC_STEP = 15
        /** Nothing heard for this long: show the rider as weak / reconnecting. */
        const val WEAK_MS = 2500L
        /** Keep audio up this long after the last rider drops, to ride out short gaps. */
        const val STANDBY_DELAY_MS = 20_000L
        const val AUTO_STOP_MS = 30 * 60_000L
        const val TALK_HOLD_FRAMES = 150 // 3 s
        const val MIC_TEST_MS = 6000L
        const val KEEPALIVE_MS = 40L
        const val TALK_IDLE_MS = 2500L
        const val TALK_MIN_MS = 4000L
        const val CALL_START_MS = 900L
        const val CALL_END_MS = 2000L
        val FIXED_PHRASES = intArrayOf(
            R.string.say_mode_music, R.string.say_mode_call, R.string.say_mic_on, R.string.say_mic_off,
            R.string.say_intercom_back, R.string.say_joined_anon, R.string.say_left_anon,
        )
    }
}
