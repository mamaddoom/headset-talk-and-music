package com.mamaddoom.ridetalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Keeps the intercom alive with the screen off: foreground service, partial
 * wake lock, low-latency Wi-Fi lock (stops Wi-Fi power save from adding
 * 100+ ms of jitter) and a multicast lock so discovery broadcasts arrive.
 */
class IntercomService : Service() {

    inner class LocalBinder : Binder() {
        val service get() = this@IntercomService
    }

    private val binder = LocalBinder()
    lateinit var engine: Engine
        private set
    private val listeners = LinkedHashSet<() -> Unit>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiPerfLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var session: MediaSession? = null
    private var lastNotificationKey = ""
    /** Whether our current foreground type includes mediaProjection. */
    private var foregroundWithProjection = false
    private var wantProjection = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Look.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Our own (language-wrapped) context, so notifications follow the app language.
        engine = Engine(this, ::onEngineState)
        // Battery saver: switch off after a long time alone.
        engine.onAutoStop = { Handler(mainLooper).post { if (engine.running) stopIntercom() } }
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startIntercom()
            ACTION_STOP -> {
                stopIntercom()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> engine.setMuted(!engine.muted)
            ACTION_PTT -> engine.headsetButton()
            ACTION_APP_AUDIO -> startAppAudio(intent)
            ACTION_APP_AUDIO_STOP -> engine.stopAppAudio()
        }
        return START_NOT_STICKY
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun startIntercom() {
        if (!goForeground()) {
            stopSelf()
            return
        }
        acquireLocks()
        startMediaSession()
        startVolumeWatch()
        engine.start()
    }

    /** Turns the user's screen-capture consent into a playback-capture session. */
    private fun startAppAudio(intent: Intent) {
        if (Build.VERSION.SDK_INT < 29 || !engine.running) return
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: return
        // Android requires a mediaProjection-type foreground service before the projection is created.
        wantProjection = true
        if (!goForeground()) {
            wantProjection = false
            return
        }
        val projection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            wantProjection = false
            goForeground()
            return
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            // The user can end sharing from the status bar.
            override fun onStop() = engine.stopAppAudio()
        }, Handler(mainLooper))
        engine.startAppAudio(projection)
    }

    fun stopIntercom() {
        engine.stop()
        stopVolumeWatch()
        session?.release()
        session = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        lastNotificationKey = ""
        wantProjection = false
        foregroundWithProjection = false
        stopSelf()
        onEngineState()
    }

    override fun onDestroy() {
        stopVolumeWatch()
        engine.release()
        session?.release()
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------- foreground

    private fun goForeground(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (Permissions.hasMic(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Permissions.hasBluetooth(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (wantProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, notification, types)
                foregroundWithProjection = wantProjection
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+ refuses if we were not started from the foreground.
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val st = if (::engine.isInitialized && engine.running) engine.state() else null
        val text = when {
            st == null -> getString(R.string.notif_starting)
            st.onCall -> getString(R.string.notif_on_call)
            st.talkSession -> getString(if (st.talkReady) R.string.talk_live else R.string.talk_preparing)
            st.peers.isEmpty() && st.standby -> getString(R.string.status_standby)
            st.peers.isEmpty() -> getString(R.string.status_searching)
            else -> getString(R.string.notif_connected, st.peers.joinToString("، ") { it.name })
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (st?.role == Settings.Role.MUSIC) {
            // Music phone of a two-phone setup: nothing to talk or mute here.
        } else if (st?.hifi != false) {
            b.addAction(action(ACTION_PTT, if (st?.talkSession == true) R.string.ptt_release else R.string.ptt_talk))
        } else if (st.ptt) {
            b.addAction(action(ACTION_PTT, if (st.pttPressed) R.string.ptt_release else R.string.ptt_talk))
        } else {
            b.addAction(action(ACTION_MUTE, if (st?.muted == true) R.string.unmute else R.string.mute))
        }
        b.addAction(action(ACTION_STOP, R.string.stop))
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun action(act: String, label: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, IntercomService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(label), pi).build()
    }

    private fun onEngineState() {
        for (l in listeners.toList()) l()
        if (!engine.running) return
        if (foregroundWithProjection && !engine.appAudioActive) {
            // Capture ended: drop the mediaProjection type (and its status-bar indicator).
            wantProjection = false
            goForeground()
        }
        val st = engine.state()
        // Only touch the notification when its visible content changes.
        val key = "${st.peers.map { it.name }}|${st.muted}|${st.ptt}|${st.pttPressed}|${st.hifi}|" +
            "${st.talkSession}|${st.talkReady}|${st.standby}|${st.onCall}"
        if (key != lastNotificationKey) {
            lastNotificationKey = key
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    // ------------------------------------------------------------- locks

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideTalk:intercom")
                .apply { setReferenceCounted(false); acquire() }
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null && Build.VERSION.SDK_INT >= 29) {
            // Low-latency mode applies while the screen is on and we are in front...
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RideTalk:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiPerfLock == null) {
            // ...high-performance (no power save) still helps with the screen off on many phones.
            // The 40 ms keepalive covers the rest.
            wifiPerfLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RideTalk:wifi-perf")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("RideTalk:discovery").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wifiPerfLock?.let { if (it.isHeld) it.release() }
        wifiPerfLock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    // ------------------------------------------------------------- hands-free controls

    private val settings by lazy { Settings(applicationContext) }
    private val pressHandler by lazy { Handler(mainLooper) }
    private var presses = 0
    private var downAtMs = 0L
    private var longFired = false
    private val resolvePresses = Runnable {
        val n = presses
        presses = 0
        if (n > 0) engine.perform(settings.gesture(Controls.headsetTrigger(n, long = false)))
    }

    private fun headsetKey(ev: KeyEvent) {
        when {
            ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 -> {
                downAtMs = ev.eventTime
                longFired = false
                pressHandler.removeCallbacks(resolvePresses)
            }
            // Headsets that repeat while held: fire the long press without waiting for release.
            ev.action == KeyEvent.ACTION_DOWN -> if (!longFired && ev.eventTime - downAtMs >= Controls.LONG_PRESS_MS) {
                longFired = true
                presses = 0
                engine.perform(settings.gesture(Controls.Trigger.HEADSET_LONG))
            }
            ev.action == KeyEvent.ACTION_UP -> {
                if (longFired) return
                if (ev.eventTime - downAtMs >= Controls.LONG_PRESS_MS) {
                    presses = 0
                    engine.perform(settings.gesture(Controls.Trigger.HEADSET_LONG))
                    return
                }
                presses++
                pressHandler.postDelayed(resolvePresses, Controls.HEADSET_WINDOW_MS)
            }
        }
    }

    /*
     * Phone volume keys: no app gets the keys themselves with the screen off,
     * but the volume change they cause is broadcast. Short combinations
     * (up-down, down-up, 3-4 presses) become controls, and the volume is put
     * back afterwards. Ordinary volume changes pass through untouched.
     */
    private val volumeSequence = Controls.VolumeSequence()
    private var volumeReceiver: BroadcastReceiver? = null
    private var seqStream = -1
    private var seqStartVolume = 0
    private var ignoreVolumeUntilMs = 0L
    private val resolveVolume = Runnable {
        val trigger = volumeSequence.resolve() ?: return@Runnable
        val action = settings.gesture(trigger)
        if (action == Controls.Action.NONE) return@Runnable
        // Undo the volume steps the combination made.
        ignoreVolumeUntilMs = SystemClock.uptimeMillis() + 500
        try {
            getSystemService(AudioManager::class.java).setStreamVolume(seqStream, seqStartVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "volume restore failed", e)
        }
        engine.perform(action)
    }

    private fun startVolumeWatch() {
        if (volumeReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val now = SystemClock.uptimeMillis()
                if (now < ignoreVolumeUntilMs) return
                val stream = i.getIntExtra(EXTRA_STREAM_TYPE, -1)
                val value = i.getIntExtra(EXTRA_STREAM_VALUE, -1)
                val prev = i.getIntExtra(EXTRA_PREV_STREAM_VALUE, -1)
                if (stream < 0 || value < 0 || prev < 0 || value == prev) return
                if (volumeSequence.press(value > prev, now)) {
                    seqStream = stream
                    seqStartVolume = prev
                }
                pressHandler.removeCallbacks(resolveVolume)
                pressHandler.postDelayed(resolveVolume, Controls.WINDOW_MS)
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, RECEIVER_EXPORTED) else registerReceiver(r, filter)
        volumeReceiver = r
    }

    private fun stopVolumeWatch() {
        volumeReceiver?.let { unregisterReceiver(it) }
        volumeReceiver = null
        pressHandler.removeCallbacks(resolveVolume)
    }

    private fun startMediaSession() {
        if (session != null) return
        val s = MediaSession(this, "RideTalk")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return false
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> headsetKey(ev)
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
                        val t = if (ev.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                            Controls.Trigger.HEADSET_NEXT
                        } else {
                            Controls.Trigger.HEADSET_PREV
                        }
                        engine.perform(settings.gesture(t))
                    }
                    else -> return false
                }
                return true
            }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        s.isActive = true
        session = s
    }

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL = "intercom"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        const val ACTION_START = "com.mamaddoom.ridetalk.START"
        const val ACTION_STOP = "com.mamaddoom.ridetalk.STOP"
        const val ACTION_MUTE = "com.mamaddoom.ridetalk.MUTE"
        const val ACTION_PTT = "com.mamaddoom.ridetalk.PTT"
        const val ACTION_APP_AUDIO = "com.mamaddoom.ridetalk.APP_AUDIO"
        const val ACTION_APP_AUDIO_STOP = "com.mamaddoom.ridetalk.APP_AUDIO_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}
