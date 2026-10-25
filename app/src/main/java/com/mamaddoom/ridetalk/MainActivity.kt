package com.mamaddoom.ridetalk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mamaddoom.ridetalk.net.Transport

class MainActivity : Activity() {

    private var service: IntercomService? = null
    private val engine get() = service?.engine
    private lateinit var settings: Settings

    private lateinit var status: TextView
    private lateinit var route: TextView
    private lateinit var btnStart: Button
    private lateinit var btnPtt: Button
    private lateinit var btnModeVox: Button
    private lateinit var btnModePtt: Button
    private lateinit var btnMute: Button
    private lateinit var onAir: TextView
    private lateinit var micMeter: ProgressBar
    private lateinit var micError: TextView
    private lateinit var voxLabel: TextView
    private lateinit var seekVox: SeekBar
    private lateinit var btnModeHifi: Button
    private lateinit var btnModeCall: Button
    private lateinit var modeNote: TextView
    private lateinit var callControls: View
    private lateinit var noiseLabel: TextView
    private lateinit var peers: TextView
    private lateinit var myIp: TextView
    private lateinit var musicTitle: TextView
    private lateinit var musicInfo: TextView
    private lateinit var btnSrcFiles: Button
    private lateinit var btnSrcApp: Button
    private lateinit var panelFiles: View
    private lateinit var panelApp: View
    private lateinit var seekPos: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnShuffle: Button
    private lateinit var btnClearList: Button
    private lateinit var trackList: LinearLayout
    private lateinit var btnAppAudio: Button
    private lateinit var appAudioStatus: TextView
    private lateinit var syncLabel: TextView
    private lateinit var driveOverlay: View
    private lateinit var driveStatus: TextView
    private lateinit var driveMusicTitle: TextView
    private lateinit var driveTalk: Button
    private lateinit var driveMusic: Button

    /** Which music panel is shown; the app-audio panel also opens itself while capturing. */
    private var showAppPanel = false
    private var seeking = false
    private var trackListKey = ""
    /** Songs picked before the service connection was ready. */
    private var pendingUris: List<Uri>? = null

    private val stateListener: () -> Unit = { render() }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var visible = false

    /** Mic meter and song position need a faster refresh than engine state events. */
    private val tick = object : Runnable {
        override fun run() {
            if (engine?.running == true) render()
            uiHandler.postDelayed(this, 150)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as IntercomService.LocalBinder).service
            service = s
            s.addListener(stateListener)
            pendingUris?.let {
                s.engine.music.add(it)
                pendingUris = null
            }
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        bindViews()
        applyInsets()
        wireControls()
        // Bound for the whole activity lifetime, not just while visible: the
        // file and screen-capture pickers put us in the background, and their
        // results must reach the same running engine.
        bindService(Intent(this, IntercomService::class.java), connection, Context.BIND_AUTO_CREATE)
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        uiHandler.post(tick)
        render()
    }

    override fun onStop() {
        visible = false
        uiHandler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        service?.removeListener(stateListener)
        service = null
        unbindService(connection)
        super.onDestroy()
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        route = findViewById(R.id.route)
        btnStart = findViewById(R.id.btnStart)
        btnPtt = findViewById(R.id.btnPtt)
        btnModeVox = findViewById(R.id.btnModeVox)
        btnModePtt = findViewById(R.id.btnModePtt)
        btnMute = findViewById(R.id.btnMute)
        onAir = findViewById(R.id.onAir)
        micMeter = findViewById(R.id.micMeter)
        micError = findViewById(R.id.micError)
        voxLabel = findViewById(R.id.voxLabel)
        seekVox = findViewById(R.id.seekVox)
        btnModeHifi = findViewById(R.id.btnModeHifi)
        btnModeCall = findViewById(R.id.btnModeCall)
        modeNote = findViewById(R.id.modeNote)
        callControls = findViewById(R.id.callControls)
        noiseLabel = findViewById(R.id.noiseLabel)
        peers = findViewById(R.id.peers)
        myIp = findViewById(R.id.myIp)
        musicTitle = findViewById(R.id.musicTitle)
        musicInfo = findViewById(R.id.musicInfo)
        btnSrcFiles = findViewById(R.id.btnSrcFiles)
        btnSrcApp = findViewById(R.id.btnSrcApp)
        panelFiles = findViewById(R.id.panelFiles)
        panelApp = findViewById(R.id.panelApp)
        seekPos = findViewById(R.id.seekPos)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        btnPlay = findViewById(R.id.btnPlay)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnClearList = findViewById(R.id.btnClearList)
        trackList = findViewById(R.id.trackList)
        btnAppAudio = findViewById(R.id.btnAppAudio)
        appAudioStatus = findViewById(R.id.appAudioStatus)
        syncLabel = findViewById(R.id.syncLabel)
        driveOverlay = findViewById(R.id.driveOverlay)
        driveStatus = findViewById(R.id.driveStatus)
        driveMusicTitle = findViewById(R.id.driveMusicTitle)
        driveTalk = findViewById(R.id.driveTalk)
        driveMusic = findViewById(R.id.driveMusic)
    }

    /** targetSdk 35 draws edge to edge; keep content clear of the system bars. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.frame)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        btnStart.setOnClickListener {
            if (engine?.running == true) {
                startService(Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_STOP))
            } else {
                val missing = Permissions.missing(this)
                if (missing.isEmpty()) startIntercom() else requestPermissions(missing, REQ_PERMS)
            }
        }

        btnPtt.setOnTouchListener(::onTalkTouch)
        driveTalk.setOnTouchListener(::onTalkTouch)
        driveMusic.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            when {
                e.music.tracks.isEmpty() -> toast(R.string.drive_no_music)
                canPlay(e) -> e.toggleMusic()
            }
        }
        findViewById<Button>(R.id.btnDrive).setOnClickListener { showDriveScreen(true) }
        findViewById<Button>(R.id.driveExit).setOnClickListener { showDriveScreen(false) }

        btnModeHifi.setOnClickListener { setAudioMode(callMode = false) }
        btnModeCall.setOnClickListener { setAudioMode(callMode = true) }
        slider(R.id.seekNoise, settings.noiseReduction) {
            settings.noiseReduction = it
            renderNoiseLabel()
        }
        renderNoiseLabel()
        switch(R.id.swBatterySaver, settings.batterySaver) {
            val e = engine
            if (e != null) e.setBatterySaver(it) else settings.batterySaver = it
        }
        switch(R.id.swCues, settings.cues) { settings.cues = it }
        switch(R.id.swAntiFeedback, settings.antiFeedback) {
            settings.antiFeedback = it
            engine?.applySettings()
        }
        switch(R.id.swDriveScreen, settings.driveKeepScreenOn) {
            settings.driveKeepScreenOn = it
            if (driveOverlay.visibility == View.VISIBLE) showDriveScreen(true)
        }
        findViewById<EditText>(R.id.editGroup).apply {
            setText(settings.groupCode)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pendingGroupCode = s?.toString().orEmpty()
                    // Apply once typing pauses, not on every key (key derivation is not free).
                    uiHandler.removeCallbacks(applyGroupCode)
                    uiHandler.postDelayed(applyGroupCode, 800)
                }
            })
        }

        btnModeVox.setOnClickListener { setPttMode(false) }
        btnModePtt.setOnClickListener { setPttMode(true) }
        btnMute.setOnClickListener { engine?.let { it.setMuted(!it.muted) } }

        slider(R.id.seekVox, settings.voxSensitivity) { settings.voxSensitivity = it }
        slider(R.id.seekGain, settings.micGain - 50) { settings.micGain = it + 50 }
        slider(R.id.seekVoice, settings.voiceVolume) { settings.voiceVolume = it }
        slider(R.id.seekMusic, settings.musicVolume) { settings.musicVolume = it }
        slider(R.id.seekSync, settings.syncDelayMs / 10) {
            settings.syncDelayMs = it * 10
            renderSyncLabel()
        }
        renderSyncLabel()

        updateVolumeStream()

        findViewById<Switch>(R.id.swDuck).apply {
            isChecked = settings.ducking
            setOnCheckedChangeListener { _, c ->
                settings.ducking = c
                engine?.applySettings()
            }
        }

        // --- music: source tabs
        btnSrcFiles.setOnClickListener {
            showAppPanel = false
            render()
        }
        btnSrcApp.setOnClickListener {
            showAppPanel = true
            render()
        }

        // --- music: playlist
        findViewById<Button>(R.id.btnPrev).setOnClickListener { engine?.let { if (canPlay(it)) it.music.previous() } }
        findViewById<Button>(R.id.btnNext).setOnClickListener { engine?.let { if (canPlay(it)) it.music.next() } }
        btnPlay.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            when {
                e.music.tracks.isEmpty() -> pickMusic()
                canPlay(e) -> e.toggleMusic()
            }
        }
        findViewById<Button>(R.id.btnAddMusic).setOnClickListener { pickMusic() }
        btnShuffle.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            e.music.shuffle = !e.music.shuffle
            settings.shuffle = e.music.shuffle
            render()
        }
        btnClearList.setOnClickListener { engine?.music?.clear() }
        seekPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = formatTime(value * 1000L)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                engine?.music?.seekTo(sb.progress * 1000L)
            }
        })

        // --- music: any app
        btnAppAudio.setOnClickListener { toggleAppAudio() }

        findViewById<EditText>(R.id.editName).apply {
            setText(settings.name)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    settings.name = s?.toString().orEmpty()
                }
            })
        }

        findViewById<Button>(R.id.btnHotspot).setOnClickListener {
            launchFirst(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
                Intent(SysSettings.ACTION_WIRELESS_SETTINGS),
            )
        }
        findViewById<Button>(R.id.btnWifi).setOnClickListener {
            launchFirst(
                if (Build.VERSION.SDK_INT >= 29) Intent(SysSettings.Panel.ACTION_WIFI) else null,
                Intent(SysSettings.ACTION_WIFI_SETTINGS),
            )
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener { launchFirst(appDetailsIntent()) }
    }

    /** Shared by the talk button and the driving screen's talk button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun onTalkTouch(v: View, ev: MotionEvent): Boolean {
        val e = engine
        if (!settings.headsetMic) {
            // Music-quality mode: a tap starts or ends a talk session.
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                if (e?.running == true) {
                    e.toggleTalk()
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    toast(R.string.start_first)
                }
            }
            return true
        }
        if (!settings.ptt) {
            // Call mode, open mic: the button mutes / unmutes.
            if (ev.actionMasked == MotionEvent.ACTION_UP) e?.let { it.setMuted(!it.muted) }
            return true
        }
        // Call mode, push-to-talk: talk while held.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                e?.setPtt(true)
                v.isActivated = true
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                e?.setPtt(false)
                v.isActivated = false
            }
        }
        return true
    }

    private var pendingGroupCode = ""
    private val applyGroupCode = Runnable {
        val e = engine
        if (e != null) e.setGroupCode(pendingGroupCode) else settings.groupCode = pendingGroupCode
    }

    private fun switch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
    }

    private fun showDriveScreen(show: Boolean) {
        driveOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && settings.driveKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        render()
    }

    // Back closes the driving screen first. (Delivered because we don't opt in to predictive back.)
    @Deprecated("Still delivered without predictive back opt-in")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (driveOverlay.visibility == View.VISIBLE) showDriveScreen(false) else super.onBackPressed()
    }

    private fun slider(id: Int, initial: Int, onChange: (Int) -> Unit) {
        findViewById<SeekBar>(id).apply {
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    onChange(value)
                    engine?.applySettings()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
    }

    /** Music needs a running intercom, and only one rider can own the music channel. */
    private fun canPlay(e: Engine): Boolean = when {
        !e.running -> {
            toast(R.string.start_first)
            false
        }
        e.remoteMusicActive() && !e.music.playing -> {
            toast(R.string.remote_music_busy)
            false
        }
        else -> true
    }

    private fun setAudioMode(callMode: Boolean) {
        val e = engine
        if (e != null) e.setHeadsetMic(callMode) else settings.headsetMic = callMode
        updateVolumeStream()
        render()
    }

    private fun renderNoiseLabel() {
        val v = settings.noiseReduction
        val level = when {
            v == 0 -> R.string.noise_off
            v < 30 -> R.string.noise_low
            v < 60 -> R.string.noise_medium
            v < 90 -> R.string.noise_high
            else -> R.string.noise_max
        }
        noiseLabel.text = getString(R.string.noise_label, getString(level))
    }

    private fun setPttMode(ptt: Boolean) {
        val e = engine
        if (e != null) e.setPttMode(ptt) else settings.ptt = ptt
        render()
    }

    private fun startIntercom() {
        if (!Permissions.hasMic(this)) toast(R.string.perm_denied)
        val i = Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_START)
        startForegroundService(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            REQ_PERMS -> startIntercom()
            REQ_MIC_FOR_APP_AUDIO -> if (Permissions.hasMic(this)) requestProjection() else toast(R.string.perm_denied)
        }
    }

    private fun pickMusic() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_MUSIC)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun toggleAppAudio() {
        val e = engine ?: return
        when {
            Build.VERSION.SDK_INT < 29 -> toast(R.string.app_audio_unsupported)
            e.appAudioActive -> startService(
                Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_APP_AUDIO_STOP),
            )
            !canPlay(e) -> Unit
            !Permissions.hasMic(this) ->
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC_FOR_APP_AUDIO)
            else -> requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Whole display: audio capture is not tied to a single shared app window.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            startActivityForResult(intent, REQ_PROJECTION)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.app_audio_failed)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_MUSIC -> onSongsPicked(data)
            REQ_PROJECTION -> startService(
                Intent(this, IntercomService::class.java)
                    .setAction(IntercomService.ACTION_APP_AUDIO)
                    .putExtra(IntercomService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(IntercomService.EXTRA_RESULT_DATA, data),
            )
        }
    }

    private fun onSongsPicked(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
        if (uris.isEmpty()) return
        val e = engine
        if (e != null) e.music.add(uris) else pendingUris = uris
        showAppPanel = false
        render()
    }

    @SuppressLint("BatteryLife") // sideloaded intercom: being killed mid-ride is the worse outcome
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            launchFirst(
                Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent(),
            )
        } else {
            // Already exempt from Doze; any vendor battery screen lives under app details.
            launchFirst(appDetailsIntent())
        }
    }

    private fun appDetailsIntent() =
        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun launchFirst(vararg intents: Intent?) {
        for (i in intents) {
            i ?: continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    private fun updateVolumeStream() {
        volumeControlStream = if (settings.headsetMic) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    private fun renderSyncLabel() {
        syncLabel.text = getString(R.string.sync_delay, settings.syncDelayMs)
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        if (!visible && service != null) return
        val e = engine
        val st = e?.takeIf { it.running }?.state()
        val running = st != null

        btnStart.isActivated = running
        btnStart.setText(if (running) R.string.stop else R.string.start)

        status.text = when {
            st == null -> getString(R.string.status_off)
            st.peers.isEmpty() && st.standby -> getString(R.string.status_standby)
            st.peers.isEmpty() -> getString(R.string.status_searching)
            else -> getString(R.string.status_connected, st.peers.size)
        }
        status.setTextColor(
            getColor(
                when {
                    st == null -> R.color.text_dim
                    st.peers.isEmpty() -> R.color.accent
                    else -> R.color.ok
                },
            ),
        )
        route.text = if (st != null) getString(R.string.route, st.route) else ""

        renderTalk(st)
        renderConnection(st)
        renderMusic(e, st)
        if (driveOverlay.visibility == View.VISIBLE) renderDrive(st)
    }

    private fun renderDrive(st: Engine.State?) {
        driveStatus.text = status.text
        driveStatus.setTextColor(status.currentTextColor)
        driveMusicTitle.text = if (st?.musicPlaying == true) st.musicTitle else ""
        val hifi = !settings.headsetMic
        driveTalk.isActivated = when {
            hifi -> st?.talkReady == true
            settings.ptt -> st?.pttPressed == true
            else -> st?.transmitting == true
        }
        driveTalk.text = getString(
            when {
                hifi && st?.talkReady == true -> R.string.talk_live
                hifi && st?.talkSession == true -> R.string.talk_preparing
                hifi -> R.string.ptt_tap
                settings.ptt -> R.string.ptt_hold
                st?.muted == true -> R.string.unmute
                else -> R.string.mute
            },
        )
        driveMusic.setText(if (st?.filePlaying == true) R.string.drive_music_pause else R.string.drive_music_play)
    }

    private fun renderTalk(st: Engine.State?) {
        val hifi = !settings.headsetMic
        btnModeHifi.isSelected = hifi
        btnModeCall.isSelected = !hifi
        modeNote.setText(if (hifi) R.string.mode_hifi_note else R.string.mode_call_note)
        callControls.visibility = if (hifi) View.GONE else View.VISIBLE

        val ptt = st?.ptt ?: settings.ptt
        btnModeVox.isSelected = !ptt
        btnModePtt.isSelected = ptt
        if (hifi) {
            btnPtt.visibility = View.VISIBLE
            btnPtt.isActivated = st?.talkReady == true
            btnPtt.text = getString(
                when {
                    st?.talkReady == true -> R.string.talk_live
                    st?.talkSession == true -> R.string.talk_preparing
                    else -> R.string.ptt_tap
                },
            )
        } else {
            btnPtt.visibility = if (ptt) View.VISIBLE else View.GONE
            btnPtt.isActivated = st?.pttPressed == true
            btnPtt.setText(R.string.ptt_hold)
        }
        // VOX sensitivity also decides when a tap-to-talk session ends.
        voxLabel.visibility = if (ptt && !hifi) View.GONE else View.VISIBLE
        seekVox.visibility = voxLabel.visibility
        val muted = st?.muted == true
        btnMute.setText(if (muted) R.string.unmute else R.string.mute)
        btnMute.isSelected = muted
        btnMute.visibility = if (ptt) View.GONE else View.VISIBLE
        onAir.visibility = if (st?.transmitting == true) View.VISIBLE else View.INVISIBLE
        micMeter.progress = st?.let { ((it.micLevelDb + 60f) / 60f * 100f).toInt().coerceIn(0, 100) } ?: 0
        micError.visibility = if (st != null && !st.micOk) View.VISIBLE else View.GONE
        micError.setText(R.string.mic_unavailable)
    }

    private fun renderConnection(st: Engine.State?) {
        peers.text = if (st == null || st.peers.isEmpty()) {
            getString(R.string.no_peers)
        } else {
            st.peers.joinToString("\n") {
                getString(
                    R.string.peer_line, it.name,
                    getString(if (it.talking) R.string.talking else R.string.idle), it.quality,
                )
            }
        }
        val ips = st?.myIps ?: Transport.localAddresses()
        myIp.text = buildString {
            append(if (ips.isEmpty()) getString(R.string.no_network) else getString(R.string.my_ip, ips.joinToString(", ")))
            if (st?.wifiBound == true) append("  ·  ").append(getString(R.string.wifi_bound))
        }
    }

    private fun renderMusic(e: Engine?, st: Engine.State?) {
        val player = e?.music
        val tracks = st?.tracks ?: player?.tracks?.map { it.title }.orEmpty()
        val index = st?.trackIndex ?: player?.index ?: 0
        val filePlaying = st?.filePlaying ?: player?.playing ?: false
        val appAudio = st?.appAudio == true
        if (appAudio) showAppPanel = true

        btnSrcFiles.isSelected = !showAppPanel
        btnSrcApp.isSelected = showAppPanel
        panelFiles.visibility = if (showAppPanel) View.GONE else View.VISIBLE
        panelApp.visibility = if (showAppPanel) View.VISIBLE else View.GONE

        musicTitle.text = when {
            st != null && st.musicPlaying && !st.musicIsLocal -> st.musicTitle
            showAppPanel -> getString(if (appAudio) R.string.app_audio_title else R.string.music_none)
            else -> tracks.getOrNull(index) ?: getString(R.string.music_none)
        }
        musicInfo.text = when {
            st?.musicError == true -> getString(R.string.music_error)
            st != null && st.musicPlaying && !st.musicIsLocal -> getString(R.string.music_from, st.musicFrom)
            else -> getString(R.string.playlist_count, tracks.size)
        }

        // Playlist panel
        btnPlay.setText(if (filePlaying) R.string.pause else R.string.play)
        btnShuffle.isSelected = st?.shuffle ?: settings.shuffle
        btnClearList.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
        val dur = st?.durationMs ?: 0L
        val pos = st?.positionMs ?: 0L
        val hasTrack = st != null && (st.filePlaying || (player?.active == true))
        seekPos.isEnabled = hasTrack && dur > 0
        if (!seeking) {
            seekPos.max = maxOf(1, (dur / 1000).toInt())
            seekPos.progress = if (hasTrack) (pos / 1000).toInt() else 0
            tvPos.text = formatTime(if (hasTrack) pos else 0)
        }
        tvDur.text = if (dur > 0) formatTime(dur) else ""
        renderTrackList(tracks, index, filePlaying)

        // App-audio panel
        btnAppAudio.setText(if (appAudio) R.string.app_audio_stop else R.string.app_audio_start)
        btnAppAudio.isSelected = appAudio
        appAudioStatus.text = when {
            st == null || !appAudio -> ""
            st.appAudioFailed -> getString(R.string.app_audio_failed)
            st.appAudioSignal -> getString(R.string.app_audio_live)
            else -> getString(R.string.app_audio_waiting)
        }
        appAudioStatus.setTextColor(getColor(if (st?.appAudioSignal == true) R.color.ok else R.color.text_dim))
    }

    /** Rebuilds the song rows only when the list or the current song changes. */
    private fun renderTrackList(tracks: List<String>, index: Int, playing: Boolean) {
        val key = "${tracks.hashCode()}|${tracks.size}|$index|$playing"
        if (key == trackListKey) return
        trackListKey = key
        trackList.removeAllViews()
        val rowHeight = dp(56)
        for ((i, title) in tracks.withIndex()) {
            val current = i == index
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.button)
                isSelected = current
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight).apply {
                    topMargin = dp(4)
                }
            }
            val label = TextView(this).apply {
                text = (if (current && playing) "▶  " else "${i + 1}.  ") + title
                setTextColor(getColor(if (current) R.color.accent else R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPaddingRelative(dp(14), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { engine?.let { if (canPlay(it)) it.playTrack(i) } }
            }
            val remove = TextView(this).apply {
                setText(R.string.remove_track)
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(rowHeight, LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener { engine?.music?.remove(i) }
            }
            row.addView(label)
            row.addView(remove)
            trackList.addView(row)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_MUSIC = 2
        const val REQ_PROJECTION = 3
        const val REQ_MIC_FOR_APP_AUDIO = 4
    }
}
