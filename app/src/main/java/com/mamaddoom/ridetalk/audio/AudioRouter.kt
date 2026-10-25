package com.mamaddoom.ridetalk.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mamaddoom.ridetalk.R

/**
 * Chooses where intercom audio goes.
 *
 * Headset mode uses the Bluetooth hands-free (SCO) link, the only Bluetooth
 * profile that carries the headset microphone. It is mono and 16 kHz (mSBC)
 * at best, so music in this mode sounds like a phone call.
 *
 * Music mode leaves the headset on A2DP (stereo, full quality) and does not
 * record; you hear the others and the music but do not transmit.
 */
class AudioRouter(private val context: Context, private val onRouteChanged: () -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var useSco = false
    private var legacyScoReceiver: BroadcastReceiver? = null

    /** The output device we asked for, so capture/playback can prefer it too. */
    @Volatile var outputDevice: AudioDeviceInfo? = null
        private set
    @Volatile var inputDevice: AudioDeviceInfo? = null
        private set
    @Volatile var routeName: String = ""
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = scheduleReroute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = scheduleReroute()
    }
    private val reroute = Runnable {
        if (!active) return@Runnable
        val before = outputDevice?.id
        apply()
        if (outputDevice?.id != before) onRouteChanged()
    }
    private var firstCallback = true

    fun start(sco: Boolean) {
        active = true
        useSco = sco
        firstCallback = true
        apply()
        am.registerAudioDeviceCallback(deviceCallback, main)
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(reroute)
        am.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            stopLegacySco()
        }
        am.mode = AudioManager.MODE_NORMAL
        outputDevice = null
        inputDevice = null
    }

    private fun scheduleReroute() {
        // The callback fires once with the current device list right after registering.
        if (firstCallback) {
            firstCallback = false
            return
        }
        main.removeCallbacks(reroute)
        main.postDelayed(reroute, 600)
    }

    private fun apply() {
        if (!useSco) {
            am.mode = AudioManager.MODE_NORMAL
            val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val a2dp = out.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: out.firstOrNull { it.type == TYPE_BLE_HEADSET || it.type == TYPE_BLE_SPEAKER }
            outputDevice = a2dp
            inputDevice = null
            routeName = a2dp?.let { context.getString(R.string.route_a2dp, it.productName) }
                ?: context.getString(R.string.route_default)
            return
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) applyModern() else applyLegacy()
    }

    @SuppressLint("NewApi")
    private fun applyModern() {
        val devices = am.availableCommunicationDevices
        val pick = PRIORITY.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
        if (pick != null) {
            val ok = am.setCommunicationDevice(pick)
            if (!ok) Log.w(TAG, "setCommunicationDevice refused for ${pick.productName}")
        }
        outputDevice = pick
        inputDevice = matchingInput(pick)
        routeName = describe(pick)
    }

    @Suppress("DEPRECATION")
    private fun applyLegacy() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val sco = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco != null) {
            startLegacySco()
            outputDevice = sco
        } else {
            stopLegacySco()
            val wired = outs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            am.isSpeakerphoneOn = wired == null
            outputDevice = wired
        }
        inputDevice = matchingInput(outputDevice)
        routeName = describe(outputDevice)
    }

    @Suppress("DEPRECATION")
    private fun startLegacySco() {
        if (legacyScoReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    // Headsets sometimes drop SCO after idle; ask again.
                    if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && active && useSco) {
                        main.postDelayed({ if (active) am.startBluetoothSco() }, 1000)
                    }
                }
            }
            context.registerReceiver(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            legacyScoReceiver = r
        }
        am.isSpeakerphoneOn = false
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopLegacySco() {
        legacyScoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        legacyScoReceiver = null
        am.isBluetoothScoOn = false
        am.stopBluetoothSco()
        am.isSpeakerphoneOn = false
    }

    private fun matchingInput(out: AudioDeviceInfo?): AudioDeviceInfo? {
        out ?: return null
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == out.type && it.address == out.address }
            ?: inputs.firstOrNull { it.type == out.type }
    }

    private fun describe(d: AudioDeviceInfo?): String = when (d?.type) {
        null -> context.getString(R.string.route_default)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(R.string.route_bt, d.productName)
        TYPE_BLE_HEADSET -> context.getString(R.string.route_ble, d.productName)
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.route_wired)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.route_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.route_earpiece)
        else -> d.productName.toString()
    }

    val isBluetooth: Boolean
        get() = outputDevice?.type.let {
            it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it == TYPE_BLE_HEADSET || it == TYPE_BLE_SPEAKER
        }

    private companion object {
        const val TAG = "AudioRouter"
        // Constant values so we can compile the list on any API level.
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
        val PRIORITY = listOf(
            TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}
