package com.mamaddoom.ridetalk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var swHeadsetMic: Switch
    private lateinit var peers: TextView
    private lateinit var myIp: TextView
    private lateinit var musicTitle: TextView
    private lateinit var musicInfo: TextView
    private lateinit var btnPlay: Button
    private lateinit var syncLabel: TextView

    private val stateListener: () -> Unit = { render() }
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Mic meter needs a faster refresh than engine state events. */
    private val meterTick = object : Runnable {
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
        render()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, IntercomService::class.java), connection, Context.BIND_AUTO_CREATE)
        uiHandler.post(meterTick)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(meterTick)
        service?.removeListener(stateListener)
        service = null
        unbindService(connection)
        super.onStop()
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
        swHeadsetMic = findViewById(R.id.swHeadsetMic)
        peers = findViewById(R.id.peers)
        myIp = findViewById(R.id.myIp)
        musicTitle = findViewById(R.id.musicTitle)
        musicInfo = findViewById(R.id.musicInfo)
        btnPlay = findViewById(R.id.btnPlay)
        syncLabel = findViewById(R.id.syncLabel)
    }

    /** targetSdk 35 draws edge to edge; keep content clear of the system bars. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
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

        btnPtt.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    engine?.setPtt(true)
                    v.isActivated = true
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    engine?.setPtt(false)
                    v.isActivated = false
                }
            }
            true
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

        swHeadsetMic.isChecked = settings.headsetMic
        swHeadsetMic.setOnCheckedChangeListener { _, checked ->
            val e = engine
            if (e != null) e.setHeadsetMic(checked) else settings.headsetMic = checked
            updateVolumeStream()
            render()
        }
        updateVolumeStream()

        findViewById<Switch>(R.id.swDuck).apply {
            isChecked = settings.ducking
            setOnCheckedChangeListener { _, c ->
                settings.ducking = c
                engine?.applySettings()
            }
        }

        findViewById<Button>(R.id.btnPrev).setOnClickListener { engine?.music?.previous() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { engine?.music?.next() }
        btnPlay.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            when {
                !e.running -> toast(R.string.start)
                e.remoteMusicActive() && !e.music.playing -> toast(R.string.remote_music_busy)
                e.music.playlist.isEmpty() -> pickMusic()
                else -> e.music.toggle()
            }
        }
        findViewById<Button>(R.id.btnAddMusic).setOnClickListener { pickMusic() }

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
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            launchFirst(
                // HyperOS / MIUI
                Intent().setClassName(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                // ColorOS (newer, then older package names)
                Intent().setClassName(
                    "com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                ),
                Intent().setClassName(
                    "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                Intent().setClassName(
                    "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                appDetailsIntent(),
            )
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
        if (requestCode == REQ_PERMS) startIntercom()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MUSIC || resultCode != RESULT_OK || data == null) return
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
        if (e != null) e.setPlaylist(uris) else settings.playlist = uris
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
            // Already exempt from Doze; OEM battery screens live under app details.
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
        val e = engine
        val st = e?.takeIf { it.running }?.state()
        val running = st != null

        btnStart.isActivated = running
        btnStart.setText(if (running) R.string.stop else R.string.start)

        status.text = when {
            st == null -> getString(R.string.status_off)
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

        // Talk
        val ptt = st?.ptt ?: settings.ptt
        btnModeVox.isSelected = !ptt
        btnModePtt.isSelected = ptt
        btnPtt.visibility = if (ptt) View.VISIBLE else View.GONE
        btnPtt.isActivated = st?.pttPressed == true
        voxLabel.visibility = if (ptt) View.GONE else View.VISIBLE
        seekVox.visibility = if (ptt) View.GONE else View.VISIBLE
        val muted = st?.muted == true
        btnMute.setText(if (muted) R.string.unmute else R.string.mute)
        btnMute.isSelected = muted
        btnMute.visibility = if (ptt) View.GONE else View.VISIBLE
        onAir.visibility = if (st?.transmitting == true) View.VISIBLE else View.INVISIBLE
        micMeter.progress = st?.let { ((it.micLevelDb + 60f) / 60f * 100f).toInt().coerceIn(0, 100) } ?: 0
        micError.visibility = if (st != null && !st.micOk) View.VISIBLE else View.GONE
        micError.setText(R.string.mic_unavailable)
        if (swHeadsetMic.isChecked != settings.headsetMic) swHeadsetMic.isChecked = settings.headsetMic

        // Connection
        peers.text = when {
            st == null -> getString(R.string.no_peers)
            st.peers.isEmpty() -> getString(R.string.no_peers)
            else -> st.peers.joinToString("\n") {
                getString(
                    R.string.peer_line, it.name,
                    getString(if (it.talking) R.string.talking else R.string.idle), it.quality,
                )
            }
        }
        val ips = st?.myIps ?: com.mamaddoom.ridetalk.net.Transport.localAddresses()
        myIp.text = buildString {
            append(if (ips.isEmpty()) getString(R.string.no_network) else getString(R.string.my_ip, ips.joinToString(", ")))
            if (st?.wifiBound == true) append("  ·  ").append(getString(R.string.wifi_bound))
        }

        // Music
        val player = e?.music
        val playlistSize = st?.playlistSize ?: settings.playlist.size
        musicTitle.text = st?.musicTitle?.takeIf { it.isNotEmpty() }
            ?: player?.title?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.music_none)
        musicInfo.text = when {
            st?.musicError == true -> getString(R.string.music_error)
            st != null && st.musicPlaying && !st.musicIsLocal -> getString(R.string.music_from, st.musicFrom)
            else -> getString(R.string.playlist_count, playlistSize)
        }
        btnPlay.setText(if (player?.playing == true) R.string.pause else R.string.play)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_MUSIC = 2
    }
}
