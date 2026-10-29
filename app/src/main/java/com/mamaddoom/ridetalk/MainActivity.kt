package com.mamaddoom.ridetalk

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mamaddoom.ridetalk.net.Transport
import com.mamaddoom.ridetalk.ui.LevelBarView
import com.mamaddoom.ridetalk.ui.TalkButtonView

/**
 * Three tabs: Intercom (big talk button, riders, link), Music (player,
 * playlist, app audio) and Settings (trip setup, sound, group, battery).
 */
class MainActivity : Activity() {

    private var service: IntercomService? = null
    private val engine get() = service?.engine
    private lateinit var settings: Settings

    // Header / navigation
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var headerArt: View
    private var headerBob: ObjectAnimator? = null
    private lateinit var pages: List<View>
    private lateinit var navItems: List<View>
    private lateinit var navIcons: List<TextView>
    private lateinit var navLabels: List<TextView>
    private lateinit var navIndicator: View
    private var tab = 0

    // Intercom tab
    private lateinit var btnStart: Button
    private lateinit var talkArea: View
    private lateinit var talkButton: TalkButtonView
    private lateinit var talkIcon: TextView
    private lateinit var talkLabel: TextView
    private lateinit var talkRoleNote: TextView
    private lateinit var micError: TextView
    private lateinit var callControls: View
    private lateinit var btnModeVox: Button
    private lateinit var btnModePtt: Button
    private lateinit var peerList: LinearLayout
    private lateinit var noPeers: TextView
    private lateinit var linkQuality: TextView
    private lateinit var route: TextView
    private lateinit var myIp: TextView
    private lateinit var wifiInfo: TextView

    // Music tab
    private lateinit var musicRoleNote: View
    private lateinit var musicCard: View
    private lateinit var musicLevels: View
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

    // Settings tab
    private lateinit var presets: List<View>
    private lateinit var twoPhonePanel: View
    private lateinit var btnPhoneMusic: Button
    private lateinit var btnPhoneMic: Button
    private lateinit var setupName: EditText
    private lateinit var editName: EditText
    private lateinit var setupFlow: TextView
    private lateinit var setupChecklist: TextView
    private lateinit var audioModeRow: View
    private lateinit var btnModeHifi: Button
    private lateinit var btnModeCall: Button
    private lateinit var modeNote: TextView
    private lateinit var modeSaved: View
    private lateinit var micSection: View
    private lateinit var noiseLabel: TextView
    private lateinit var voxLabel: TextView
    private lateinit var seekVox: SeekBar
    private lateinit var micMeter: LevelBarView
    private lateinit var btnMicTest: Button

    // Driving screen
    private lateinit var driveOverlay: View
    private lateinit var driveStatus: TextView
    private lateinit var driveMusicTitle: TextView
    private lateinit var driveTalk: Button
    private lateinit var driveMusic: Button

    /** Which music panel is shown; the app-audio panel also opens itself while capturing. */
    private var showAppPanel = false
    private var seeking = false
    private var trackListKey = ""
    private var peerListKey = ""
    private var statusPulse: ObjectAnimator? = null
    /** Songs picked before the service connection was ready. */
    private var pendingUris: List<Uri>? = null

    private val stateListener: () -> Unit = { render() }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var visible = false

    /** Mic meter and song position need a faster refresh than engine state events. */
    private val tick = object : Runnable {
        override fun run() {
            if (engine?.running == true) render()
            uiHandler.postDelayed(this, 120)
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
        selectTab(savedInstanceState?.getInt(KEY_TAB) ?: 0, animate = false)
        // Bound for the whole activity lifetime, not just while visible: the
        // file and screen-capture pickers put us in the background, and their
        // results must reach the same running engine.
        bindService(Intent(this, IntercomService::class.java), connection, Context.BIND_AUTO_CREATE)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
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
        statusPulse?.cancel()
        headerBob?.cancel()
        service?.removeListener(stateListener)
        service = null
        unbindService(connection)
        super.onDestroy()
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        headerArt = findViewById(R.id.headerArt)
        pages = listOf(R.id.pageHome, R.id.pageMusic, R.id.pageSettings).map { findViewById(it) }
        navItems = listOf(R.id.navHome, R.id.navMusic, R.id.navSettings).map { findViewById(it) }
        navIcons = listOf(R.id.navHomeIcon, R.id.navMusicIcon, R.id.navSettingsIcon).map { findViewById(it) }
        navLabels = listOf(R.id.navHomeLabel, R.id.navMusicLabel, R.id.navSettingsLabel).map { findViewById(it) }
        navIndicator = findViewById(R.id.navIndicator)

        btnStart = findViewById(R.id.btnStart)
        talkArea = findViewById(R.id.talkArea)
        talkButton = findViewById(R.id.talkButton)
        talkIcon = findViewById(R.id.talkIcon)
        talkLabel = findViewById(R.id.talkLabel)
        talkRoleNote = findViewById(R.id.talkRoleNote)
        micError = findViewById(R.id.micError)
        callControls = findViewById(R.id.callControls)
        btnModeVox = findViewById(R.id.btnModeVox)
        btnModePtt = findViewById(R.id.btnModePtt)
        peerList = findViewById(R.id.peerList)
        noPeers = findViewById(R.id.noPeers)
        linkQuality = findViewById(R.id.linkQuality)
        route = findViewById(R.id.route)
        myIp = findViewById(R.id.myIp)
        wifiInfo = findViewById(R.id.wifiInfo)

        musicRoleNote = findViewById(R.id.musicRoleNote)
        musicCard = findViewById(R.id.musicCard)
        musicLevels = findViewById(R.id.musicLevels)
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

        presets = listOf(R.id.preset1, R.id.preset2).map { findViewById(it) }
        twoPhonePanel = findViewById(R.id.twoPhonePanel)
        btnPhoneMusic = findViewById(R.id.btnPhoneMusic)
        btnPhoneMic = findViewById(R.id.btnPhoneMic)
        setupName = findViewById(R.id.setupName)
        editName = findViewById(R.id.editName)
        setupFlow = findViewById(R.id.setupFlow)
        setupChecklist = findViewById(R.id.setupChecklist)
        audioModeRow = findViewById(R.id.audioModeRow)
        btnModeHifi = findViewById(R.id.btnModeHifi)
        btnModeCall = findViewById(R.id.btnModeCall)
        modeNote = findViewById(R.id.modeNote)
        modeSaved = findViewById(R.id.modeSaved)
        micSection = findViewById(R.id.micSection)
        noiseLabel = findViewById(R.id.noiseLabel)
        voxLabel = findViewById(R.id.voxLabel)
        seekVox = findViewById(R.id.seekVox)
        micMeter = findViewById(R.id.micMeter)
        btnMicTest = findViewById(R.id.btnMicTest)

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

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int, animate: Boolean = true) {
        val old = tab
        tab = index.coerceIn(0, pages.size - 1)
        for ((i, label) in navLabels.withIndex()) {
            label.setTextColor(getColor(if (i == tab) R.color.accent else R.color.text_dim))
        }
        moveIndicator(animate)
        if (!animate || old == tab) {
            for ((i, p) in pages.withIndex()) {
                p.visibility = if (i == tab) View.VISIBLE else View.GONE
                p.alpha = 1f
                p.translationX = 0f
            }
            render()
            return
        }
        // Slide in the direction of travel (mirrored in RTL), with a cross-fade.
        val rtl = window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val dir = (if (tab > old) 1 else -1) * (if (rtl) -1 else 1)
        val shift = dp(28).toFloat()
        val out = pages[old]
        out.animate().cancel()
        out.animate().alpha(0f).translationX(-dir * shift).setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                out.visibility = View.GONE
                out.translationX = 0f
            }.start()
        val inc = pages[tab]
        inc.animate().cancel()
        inc.alpha = 0f
        inc.translationX = dir * shift
        inc.visibility = View.VISIBLE
        inc.animate().alpha(1f).translationX(0f).setDuration(240).setStartDelay(60)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        staggerCards(inc)
        // Little bounce on the tab icon.
        navIcons[tab].apply {
            scaleX = 0.8f; scaleY = 0.8f
            animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(3f)).start()
        }
        render()
    }

    /** Cards of a freshly shown page rise into place one after another. */
    private fun staggerCards(page: View) {
        val column = (page as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        var delay = 80L
        for (i in 0 until column.childCount) {
            val c = column.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            c.alpha = 0f
            c.translationY = dp(14).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
            delay += 45
        }
    }

    private fun moveIndicator(animate: Boolean) {
        val item = navItems[tab]
        if (item.width == 0) {
            item.post { moveIndicator(false) }
            return
        }
        val lp = navIndicator.layoutParams
        if (lp.width != item.width) {
            lp.width = item.width
            navIndicator.layoutParams = lp
        }
        val x = item.left.toFloat()
        if (animate) {
            navIndicator.animate().translationX(x).setDuration(280).setInterpolator(OvershootInterpolator(0.8f)).start()
        } else {
            navIndicator.translationX = x
        }
    }

    // ------------------------------------------------------------------ controls

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        navItems.forEachIndexed { i, v -> v.setOnClickListener { if (i != tab) selectTab(i) } }

        btnStart.setOnClickListener {
            pop(it)
            if (engine?.running == true) {
                startService(Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_STOP))
            } else {
                val missing = Permissions.missing(this)
                if (missing.isEmpty()) startIntercom() else requestPermissions(missing, REQ_PERMS)
            }
        }

        talkButton.setOnTouchListener(::onTalkTouch)
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
        findViewById<Button>(R.id.btnVolume).setOnClickListener {
            getSystemService(AudioManager::class.java)
                .adjustStreamVolume(volumeControlStream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }

        presets[0].setOnClickListener { applySetup(twoPhones = false, talkPhone = settings.isTalkPhone) }
        presets[1].setOnClickListener { applySetup(twoPhones = true, talkPhone = settings.isTalkPhone) }
        btnPhoneMusic.setOnClickListener { applySetup(twoPhones = true, talkPhone = false) }
        btnPhoneMic.setOnClickListener { applySetup(twoPhones = true, talkPhone = true) }

        btnMicTest.setOnClickListener {
            val e = engine
            if (e?.running == true) e.startMicTest() else toast(R.string.start_first)
        }

        btnModeHifi.setOnClickListener { setAudioMode(callMode = false) }
        btnModeCall.setOnClickListener { setAudioMode(callMode = true) }
        btnModeVox.setOnClickListener { setPttMode(false) }
        btnModePtt.setOnClickListener { setPttMode(true) }

        slider(R.id.seekNoise, settings.noiseReduction) {
            settings.noiseReduction = it
            renderNoiseLabel()
        }
        renderNoiseLabel()
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

        switch(R.id.swDuck, settings.ducking) {
            settings.ducking = it
            engine?.applySettings()
        }
        switch(R.id.swBatterySaver, settings.batterySaver) {
            val e = engine
            if (e != null) e.setBatterySaver(it) else settings.batterySaver = it
        }
        switch(R.id.swCues, settings.cues) { settings.cues = it }
        switch(R.id.swRxDenoise, settings.rxDenoise) {
            settings.rxDenoise = it
            engine?.applySettings()
        }
        switch(R.id.swEchoGuard, settings.echoGuard) {
            settings.echoGuard = it
            engine?.applySettings()
        }
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
            addTextChangedListener(afterChange {
                pendingGroupCode = it
                // Apply once typing pauses, not on every key (key derivation is not free).
                uiHandler.removeCallbacks(applyGroupCode)
                uiHandler.postDelayed(applyGroupCode, 800)
            })
        }
        // Two name fields (network card, two-phone setup) edit the same name.
        for (field in listOf(editName, setupName)) {
            field.setText(settings.name)
            field.addTextChangedListener(afterChange {
                if (!field.hasFocus()) return@afterChange
                settings.name = it
                val other = if (field === editName) setupName else editName
                if (other.text.toString() != settings.name) other.setText(settings.name)
            })
        }

        // Music
        btnSrcFiles.setOnClickListener {
            showAppPanel = false
            render()
        }
        btnSrcApp.setOnClickListener {
            showAppPanel = true
            render()
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { engine?.let { if (canPlay(it)) it.music.previous() } }
        findViewById<Button>(R.id.btnNext).setOnClickListener { engine?.let { if (canPlay(it)) it.music.next() } }
        btnPlay.setOnClickListener {
            pop(it)
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
        btnAppAudio.setOnClickListener { toggleAppAudio() }

        // Network & reliability
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

    /** Shared by the big talk button and the driving screen's talk button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun onTalkTouch(v: View, ev: MotionEvent): Boolean {
        // Press feedback: the button (with its label) sinks a little and springs back.
        val target = if (v === talkButton) talkArea else v
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
        val e = engine
        if (settings.role == Settings.Role.MUSIC) return true
        if (e?.running != true) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) toast(R.string.start_first)
            return true
        }
        if (tapToTalk) {
            // Music-quality mode: a tap starts or ends a talk session.
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                e.toggleTalk()
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            return true
        }
        if (!settings.ptt) {
            // Call mode, open mic: the button mutes / unmutes.
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                e.setMuted(!e.muted)
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            return true
        }
        // Call mode, push-to-talk: talk while held.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                e.setPtt(true)
                v.isActivated = true
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                e.setPtt(false)
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

    private fun afterChange(block: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
    }

    private fun switch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
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

    /** Small "pop" when a primary button is tapped. */
    private fun pop(v: View) {
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun showDriveScreen(show: Boolean) {
        if (show) {
            driveOverlay.alpha = 0f
            driveOverlay.visibility = View.VISIBLE
            driveOverlay.animate().alpha(1f).setDuration(220).start()
        } else {
            driveOverlay.animate().alpha(0f).setDuration(180).withEndAction { driveOverlay.visibility = View.GONE }.start()
        }
        if (show && settings.driveKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        render()
    }

    // Back closes the driving screen first, then returns to the first tab.
    // (Delivered because we don't opt in to predictive back.)
    @Deprecated("Still delivered without predictive back opt-in")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            driveOverlay.visibility == View.VISIBLE -> showDriveScreen(false)
            tab != 0 -> selectTab(0)
            else -> super.onBackPressed()
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

    /** Music-quality mode with the headset mic: talking needs a tap. */
    private val tapToTalk: Boolean
        get() = !settings.headsetMic && settings.role == Settings.Role.NORMAL

    private fun applySetup(twoPhones: Boolean, talkPhone: Boolean) {
        val e = engine
        if (e != null) {
            e.setSetup(twoPhones, talkPhone)
        } else {
            settings.twoPhones = twoPhones
            settings.isTalkPhone = talkPhone
        }
        updateVolumeStream()
        render()
    }

    private fun setAudioMode(callMode: Boolean) {
        val e = engine
        if (e != null) e.setHeadsetMic(callMode) else settings.headsetMic = callMode
        updateVolumeStream()
        render()
    }

    private fun setPttMode(ptt: Boolean) {
        val e = engine
        if (e != null) e.setPttMode(ptt) else settings.ptt = ptt
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

    private fun renderSyncLabel() {
        syncLabel.text = getString(R.string.sync_delay, settings.syncDelayMs)
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
        // Volume keys follow whichever stream is actually audible.
        val callLink = settings.role == Settings.Role.TALK ||
            (settings.role == Settings.Role.NORMAL && settings.headsetMic)
        volumeControlStream = if (callLink) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        if (!visible && service != null) return
        val e = engine
        val st = e?.takeIf { it.running }?.state()
        renderHeader(st)
        when (tab) {
            0 -> renderHome(st)
            1 -> renderMusic(e, st)
            2 -> renderSettings(st)
        }
        if (driveOverlay.visibility == View.VISIBLE) renderDrive(st)
    }

    private fun renderHeader(st: Engine.State?) {
        val (text, colour) = when {
            st == null -> getString(R.string.status_off) to R.color.text_dim
            st.peers.isEmpty() && st.standby -> getString(R.string.status_standby) to R.color.accent
            st.peers.isEmpty() -> getString(R.string.status_searching) to R.color.accent
            else -> getString(R.string.status_connected, st.peers.size) to R.color.ok
        }
        if (status.text != text) status.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colour))
        // The dot breathes while we are looking for riders.
        val searching = st != null && st.peers.isEmpty()
        if (searching && statusPulse == null) {
            statusPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!searching && statusPulse != null) {
            statusPulse?.cancel()
            statusPulse = null
            statusDot.alpha = 1f
        }
        // The helmet rocks gently, like riding, while someone is connected.
        val riding = st != null && st.peers.isNotEmpty()
        if (riding && headerBob == null) {
            headerArt.pivotX = headerArt.width / 2f
            headerArt.pivotY = headerArt.height.toFloat()
            headerBob = ObjectAnimator.ofFloat(headerArt, View.ROTATION, -5f, 5f).apply {
                duration = 1400
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        } else if (!riding && headerBob != null) {
            headerBob?.cancel()
            headerBob = null
            headerArt.animate().rotation(0f).setDuration(300).start()
        }
    }

    private fun renderHome(st: Engine.State?) {
        val running = st != null
        btnStart.isActivated = running
        btnStart.setText(if (running) R.string.stop else R.string.start)

        val role = settings.role
        val tap = tapToTalk
        val ptt = st?.ptt ?: settings.ptt
        talkArea.visibility = if (role == Settings.Role.MUSIC) View.GONE else View.VISIBLE
        val roleNote = when (role) {
            Settings.Role.MUSIC -> R.string.talk_role_listen
            Settings.Role.TALK -> R.string.talk_role_mic
            Settings.Role.NORMAL -> 0
        }
        talkRoleNote.visibility = if (roleNote != 0) View.VISIBLE else View.GONE
        if (roleNote != 0) talkRoleNote.setText(roleNote)
        callControls.visibility = if (tap || role == Settings.Role.MUSIC) View.GONE else View.VISIBLE
        btnModeVox.isSelected = !ptt
        btnModePtt.isSelected = ptt

        // Talk circle: colour, label and icon follow the state.
        val mode: TalkButtonView.Mode
        val label: Int
        val icon: String
        when {
            st == null -> { mode = TalkButtonView.Mode.OFF; label = R.string.talk_off; icon = "🎙" }
            tap && st.talkReady -> { mode = TalkButtonView.Mode.LIVE; label = R.string.talk_live; icon = "🎙" }
            tap && st.talkSession -> { mode = TalkButtonView.Mode.PREPARING; label = R.string.talk_preparing; icon = "⏳" }
            tap -> { mode = TalkButtonView.Mode.IDLE; label = R.string.ptt_tap; icon = "🎙" }
            ptt -> {
                mode = if (st.pttPressed) TalkButtonView.Mode.LIVE else TalkButtonView.Mode.IDLE
                label = if (st.pttPressed) R.string.talk_on_air else R.string.ptt_hold
                icon = "🎙"
            }
            st.muted -> { mode = TalkButtonView.Mode.MUTED; label = R.string.talk_muted; icon = "🔇" }
            st.transmitting -> { mode = TalkButtonView.Mode.LIVE; label = R.string.talk_on_air; icon = "🎙" }
            else -> { mode = TalkButtonView.Mode.IDLE; label = R.string.talk_open; icon = "🎙" }
        }
        talkButton.setMode(mode)
        talkButton.setLevel(st?.let { ((it.micLevelDb + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f)
        talkLabel.setText(label)
        if (talkIcon.text != icon) talkIcon.text = icon
        micError.visibility = if (st != null && !st.micOk && role != Settings.Role.MUSIC) View.VISIBLE else View.GONE

        renderPeers(st)

        if (st == null || st.standby) {
            linkQuality.text = getString(if (st == null) R.string.status_off else R.string.status_standby)
            linkQuality.setTextColor(getColor(R.color.text_dim))
            route.text = ""
        } else {
            linkQuality.setText(
                when {
                    st.leAudio -> R.string.link_le
                    st.linkHighQuality -> R.string.link_hq
                    else -> R.string.link_narrow
                },
            )
            linkQuality.setTextColor(getColor(if (st.linkHighQuality) R.color.ok else R.color.accent))
            route.text = getString(R.string.route, st.route)
        }
        val ips = st?.myIps ?: Transport.localAddresses()
        myIp.text = buildString {
            append(if (ips.isEmpty()) getString(R.string.no_network) else getString(R.string.my_ip, ips.joinToString(", ")))
            if (st?.wifiBound == true) append("  ·  ").append(getString(R.string.wifi_bound))
        }
        // Hotspot client: show the signal, and suggest 2.4 GHz (longer range) when on 5 GHz.
        val rssi = st?.wifiRssi
        if (rssi == null) {
            wifiInfo.visibility = View.GONE
        } else {
            val mhz = st.wifiMhz ?: 0
            val quality = when {
                rssi >= -60 -> R.string.wifi_strong
                rssi >= -72 -> R.string.wifi_ok
                else -> R.string.wifi_weak
            }
            val band = getString(if (mhz in 1..3000) R.string.wifi_24 else R.string.wifi_5)
            wifiInfo.text = getString(R.string.wifi_line, getString(quality), rssi, band) +
                if (mhz > 3000) "\n" + getString(R.string.wifi_5_tip) else ""
            wifiInfo.setTextColor(getColor(if (rssi >= -72) R.color.text_dim else R.color.accent))
            wifiInfo.visibility = View.VISIBLE
        }
    }

    /** Rider chips: rebuilt when the set changes; the talking glow animates in place. */
    private fun renderPeers(st: Engine.State?) {
        val list = st?.peers.orEmpty()
        noPeers.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val key = list.joinToString("|") { "${it.name}/${it.talkPhone}/${it.musicPhone}/${it.mine}" }
        if (key != peerListKey) {
            peerListKey = key
            peerList.removeAllViews()
            for (p in list) {
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.chip)
                    setPaddingRelative(dp(16), 0, dp(16), 0)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                        topMargin = dp(6)
                    }
                }
                val icon = when {
                    p.talkPhone -> "🎙"
                    p.musicPhone -> "🎵"
                    else -> "🏍"
                }
                chip.addView(TextView(this).apply {
                    text = icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                })
                chip.addView(TextView(this).apply {
                    text = if (p.mine) "${p.name}  ${getString(R.string.peer_mine)}" else p.name
                    setTextColor(getColor(R.color.text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPaddingRelative(dp(12), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                chip.addView(TextView(this).apply {
                    tag = "detail"
                    setTextColor(getColor(R.color.text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                peerList.addView(chip)
            }
        }
        for ((i, p) in list.withIndex()) {
            val chip = peerList.getChildAt(i) ?: continue
            chip.isActivated = p.talking && !p.weak
            chip.alpha = if (p.weak) 0.6f else 1f
            val detail = chip.findViewWithTag<TextView>("detail")
            val text = when {
                p.weak -> getString(R.string.peer_weak)
                p.talking -> getString(R.string.peer_talking)
                else -> "${p.quality}%"
            }
            if (detail.text != text) detail.text = text
            detail.setTextColor(getColor(when {
                p.weak -> R.color.accent
                p.talking -> R.color.ok
                else -> R.color.text_dim
            }))
        }
    }

    private fun renderMusic(e: Engine?, st: Engine.State?) {
        val role = settings.role
        val talkPhone = role == Settings.Role.TALK
        musicRoleNote.visibility = if (talkPhone) View.VISIBLE else View.GONE
        musicCard.visibility = if (talkPhone) View.GONE else View.VISIBLE
        musicLevels.visibility = musicCard.visibility
        if (talkPhone) return

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
                    topMargin = dp(6)
                }
            }
            val label = TextView(this).apply {
                text = (if (current && playing) "▶  " else "${i + 1}.  ") + title
                setTextColor(getColor(if (current) R.color.accent else R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPaddingRelative(dp(16), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { engine?.let { if (canPlay(it)) it.playTrack(i) } }
            }
            val remove = TextView(this).apply {
                setText(R.string.remove_track)
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(rowHeight, LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener { engine?.music?.remove(i) }
            }
            row.addView(label)
            row.addView(remove)
            trackList.addView(row)
        }
    }

    private fun renderSettings(st: Engine.State?) {
        // Trip setup
        val two = settings.twoPhones
        val talkPhone = settings.isTalkPhone
        presets[0].isSelected = !two
        presets[1].isSelected = two
        twoPhonePanel.visibility = if (two) View.VISIBLE else View.GONE
        btnPhoneMusic.isSelected = two && !talkPhone
        btnPhoneMic.isSelected = two && talkPhone
        if (!setupName.hasFocus() && setupName.text.toString() != settings.name) setupName.setText(settings.name)
        if (!editName.hasFocus() && editName.text.toString() != settings.name) editName.setText(settings.name)

        val role = settings.role
        val (mic, out, quality) = when (role) {
            Settings.Role.MUSIC -> Triple(R.string.src_none, R.string.out_headset_right, R.string.q_full_mono)
            Settings.Role.TALK -> Triple(R.string.src_headset_mic, R.string.out_none, R.string.q_none)
            Settings.Role.NORMAL -> Triple(
                R.string.src_headset_mic,
                R.string.out_headset,
                when {
                    !settings.headsetMic -> R.string.q_tap
                    st?.leAudio == true -> R.string.q_full
                    else -> R.string.q_call
                },
            )
        }
        setupFlow.text = listOf(
            getString(R.string.flow_mic, getString(mic)),
            getString(R.string.flow_out, getString(out)),
            getString(R.string.flow_quality, getString(quality)),
        ).joinToString("\n")

        val checks = ArrayList<String>()
        checks += getString(if (st != null) R.string.chk_running else R.string.chk_start)
        checks += getString(if (st?.headsetConnected == true) R.string.chk_headset_ok else R.string.chk_headset_missing)
        if (two) {
            checks += getString(if (talkPhone) R.string.chk_one_ear_left else R.string.chk_one_ear_right)
            if (st != null) {
                checks += if (st.otherPhoneConnected) {
                    getString(R.string.chk_other_ok)
                } else {
                    getString(
                        R.string.chk_other_missing,
                        getString(if (talkPhone) R.string.two_phone_music else R.string.two_phone_mic).replace("\n", " · "),
                    )
                }
            }
        }
        setupChecklist.text = checks.joinToString("\n")

        // Sound
        audioModeRow.visibility = if (role == Settings.Role.NORMAL) View.VISIBLE else View.GONE
        modeNote.visibility = audioModeRow.visibility
        modeSaved.visibility = audioModeRow.visibility
        btnModeHifi.isSelected = !settings.headsetMic
        btnModeCall.isSelected = settings.headsetMic
        modeNote.setText(if (!settings.headsetMic) R.string.mode_hifi_note else R.string.mode_call_note)
        micSection.visibility = if (role == Settings.Role.MUSIC) View.GONE else View.VISIBLE
        val ptt = st?.ptt ?: settings.ptt
        // Detector sensitivity matters for open mic and for ending a tap-to-talk session.
        voxLabel.visibility = if (ptt && !tapToTalk) View.GONE else View.VISIBLE
        seekVox.visibility = voxLabel.visibility
        micMeter.setLevel(st?.let { ((it.micLevelDb + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f, st?.transmitting == true)
        val testLeft = st?.micTestLeftS ?: 0
        btnMicTest.isSelected = testLeft > 0
        btnMicTest.text = if (testLeft > 0) getString(R.string.mic_test_running, testLeft) else getString(R.string.mic_test)
    }

    private fun renderDrive(st: Engine.State?) {
        driveStatus.text = status.text
        driveStatus.setTextColor(getColor(if (st != null && st.peers.isNotEmpty()) R.color.ok else R.color.accent))
        driveMusicTitle.text = if (st?.musicPlaying == true) st.musicTitle else ""
        val tap = tapToTalk
        driveTalk.visibility = if (settings.role == Settings.Role.MUSIC) View.GONE else View.VISIBLE
        driveMusic.visibility = if (settings.role == Settings.Role.TALK) View.GONE else View.VISIBLE
        driveTalk.isActivated = when {
            tap -> st?.talkReady == true
            settings.ptt -> st?.pttPressed == true
            else -> st?.transmitting == true
        }
        driveTalk.text = getString(
            when {
                tap && st?.talkReady == true -> R.string.talk_live
                tap && st?.talkSession == true -> R.string.talk_preparing
                tap -> R.string.ptt_tap
                settings.ptt -> R.string.ptt_hold
                st?.muted == true -> R.string.unmute
                else -> R.string.mute
            },
        )
        driveMusic.setText(if (st?.filePlaying == true) R.string.drive_music_pause else R.string.drive_music_play)
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
        const val KEY_TAB = "tab"
    }
}
