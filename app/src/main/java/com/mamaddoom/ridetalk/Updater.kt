package com.mamaddoom.ridetalk

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the project's GitHub releases: finds the newest
 * release, downloads its APK with the system download manager and opens the
 * installer. Needs internet only while checking and downloading.
 */
class Updater(private val context: Context, private val onChange: () -> Unit) {

    enum class Status { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, FAILED }

    @Volatile var status = Status.IDLE
        private set
    /** Version name of the newest release ("1.0.15"), once known. */
    @Volatile var latestName = ""
        private set
    private var apkUrl = ""
    private var downloadId = -1L
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    val currentCode: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val currentName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Checks at most every [AUTO_INTERVAL_MS]; quiet when offline. */
    fun autoCheck() {
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last > AUTO_INTERVAL_MS) check()
    }

    fun check() {
        if (status == Status.CHECKING || status == Status.DOWNLOADING) return
        set(Status.CHECKING)
        Thread({
            val result = try {
                fetchLatest()
            } catch (_: Exception) {
                null
            }
            main.post {
                if (result == null) {
                    set(Status.FAILED)
                    return@post
                }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                val (code, name, url) = result
                latestName = name
                apkUrl = url
                set(if (code > currentCode && url.isNotEmpty()) Status.AVAILABLE else Status.LATEST)
            }
        }, "updater").start()
    }

    /** (version code, version name, APK url) of the newest release. */
    private fun fetchLatest(): Triple<Long, String, String> {
        val c = URL(LATEST_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            // Online beta: newest release whose tag is "online-v…" (the main app's releases are separate).
            val all = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var json: JSONObject? = null
            for (i in 0 until all.length()) {
                val r = all.getJSONObject(i)
                if (!r.optBoolean("draft") && r.optString("tag_name").startsWith(TAG_PREFIX)) {
                    json = r
                    break
                }
            }
            if (json == null) return Triple(0L, "", "")
            // Tags are "online-v1.0.<build>"; the build number is the version code.
            val name = json.getString("tag_name").removePrefix(TAG_PREFIX)
            val code = name.substringAfterLast('.').toLongOrNull() ?: 0L
            val assets = json.getJSONArray("assets")
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            return Triple(code, name, url)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads the new APK and opens the installer. Returns false (and opens
     * the system screen for it) when Android first needs the user to allow
     * this app to install updates.
     */
    fun downloadAndInstall(): Boolean {
        if (status != Status.AVAILABLE || apkUrl.isEmpty()) return true
        if (!context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        val file = "HelmetMediaOnlineBeta-$latestName.apk"
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(file)?.delete()
        val req = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Helmet Media Online Beta $latestName")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file)
        listenForDownload()
        downloadId = dm.enqueue(req)
        set(Status.DOWNLOADING)
        return true
    }

    private var receiver: BroadcastReceiver? = null

    private fun listenForDownload() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                val dm = context.getSystemService(DownloadManager::class.java)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    set(Status.FAILED)
                    return
                }
                set(Status.AVAILABLE)
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(install)
                } catch (_: Exception) {
                    set(Status.FAILED)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // Sent by the system download provider, another app: must be exported on Android 13+.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(r, filter)
        receiver = r
    }

    fun release() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }

    private fun set(s: Status) {
        status = s
        onChange()
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/mamaddoom/headset-talk-and-music/releases?per_page=20"
        private const val TAG_PREFIX = "online-v"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
