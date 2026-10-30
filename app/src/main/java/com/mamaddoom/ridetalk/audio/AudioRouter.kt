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
        // Undo only the call mode we set ourselves, and never under a phone call.
        if (useSco && !telephonyCall()) am.mode = AudioManager.MODE_NORMAL
        outputDevice = null
        inputDevice = null
    }

    /**
     * A phone call is ringing or running: a cellular call, or (while we are not
     * holding a call link ourselves) a call in another app such as WhatsApp.
     */
    fun phoneCallActive(): Boolean =
        telephonyCall() || (am.mode == AudioManager.MODE_IN_COMMUNICATION && !(active && useSco))

    private fun telephonyCall(): Boolean = when (am.mode) {
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL,
        MODE_CALL_SCREENING, MODE_CALL_REDIRECT, MODE_COMMUNICATION_REDIRECT -> true
        else -> false
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
            if (!phoneCallActive()) am.mode = AudioManager.MODE_NORMAL
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

    /** A Bluetooth headset is connected at all (stereo or call link). */
    fun bluetoothHeadsetPresent(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == TYPE_BLE_HEADSET || it.type == TYPE_BLE_SPEAKER
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

    /**
     * True when audio goes over a classic Bluetooth call link (HFP/SCO): mono,
     * 16 kHz at best. LE Audio, A2DP, wired and the speaker are full quality.
     */
    val isNarrowband: Boolean
        get() = outputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /** Sound comes out of the phone itself, where speaker-to-mic feedback loops are possible. */
    val isLoudspeaker: Boolean
        get() = when (outputDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> true
            null -> !bluetoothHeadsetPresent() && am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            else -> false
        }

    val isLeAudio: Boolean
        get() = outputDevice?.type.let { it == TYPE_BLE_HEADSET || it == TYPE_BLE_SPEAKER }

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
        const val MODE_CALL_SCREENING = 4
        const val MODE_CALL_REDIRECT = 5
        const val MODE_COMMUNICATION_REDIRECT = 6
        val PRIORITY = listOf(
            TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}
