package com.mamaddoom.ridetalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onCreate() {
        super.onCreate()
        engine = Engine(applicationContext, ::onEngineState)
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
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val st = if (::engine.isInitialized && engine.running) engine.state() else null
        val text = when {
            st == null -> getString(R.string.notif_starting)
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
            "${st.talkSession}|${st.talkReady}|${st.standby}"
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

    // ------------------------------------------------------------- headset button

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
                if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> engine.headsetButton()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> engine.music.next()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> engine.music.previous()
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
