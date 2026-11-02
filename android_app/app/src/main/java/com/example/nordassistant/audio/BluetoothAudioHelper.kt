package com.example.nordassistant.audio

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

/**
 * Manages dynamic Bluetooth audio routing (microphone input & speech output)
 * ensuring voice input from Bluetooth earbuds (e.g. OnePlus Nord Buds) is
 * cleanly captured by SpeechRecognizer and AudioRecord.
 */
object BluetoothAudioHelper {
    private const val TAG = "BluetoothAudioHelper"

    private var audioManager: AudioManager? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScoActive = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        registerDeviceCallback(am)
        registerScoReceiver(app)
        routeToBluetoothHeadsetIfAvailable()
    }

    private fun registerDeviceCallback(am: AudioManager) {
        if (deviceCallback != null) return
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio devices added, checking for Bluetooth earbuds/headset...")
                mainHandler.postDelayed({ routeToBluetoothHeadsetIfAvailable() }, 300)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio devices removed, refreshing audio routing...")
                mainHandler.postDelayed({ routeToBluetoothHeadsetIfAvailable() }, 300)
            }
        }
        am.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    private fun registerScoReceiver(context: Context) {
        if (scoReceiver != null) return
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d(TAG, "Bluetooth SCO state changed: $state (Connected=${AudioManager.SCO_AUDIO_STATE_CONNECTED})")
                isScoActive = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
            }
        }
        try {
            @Suppress("DEPRECATION")
            context.registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SCO broadcast receiver", e)
        }
    }

    /**
     * Finds and binds the connected Bluetooth headset/earbuds for communication.
     * When set, Android routes microphone input directly from the Bluetooth earbuds.
     */
    fun routeToBluetoothHeadsetIfAvailable(): Boolean {
        val am = audioManager ?: return false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val availableDevices = am.availableCommunicationDevices
                Log.d(TAG, "Evaluating available comm devices: ${availableDevices.map { "${it.productName} (type=${it.type})" }}")

                val bluetoothDevice = availableDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID
                }

                if (bluetoothDevice != null) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    val success = am.setCommunicationDevice(bluetoothDevice)
                    isScoActive = success
                    if (success) {
                        // Boost mic gain for low-voice earbud input so quiet speech is captured
                        boostMicSensitivity(am)
                    }
                    Log.i(TAG, "🎧 Successfully routed microphone to Bluetooth earbuds: ${bluetoothDevice.productName} (success: $success)")
                    return success
                } else {
                    Log.d(TAG, "No Bluetooth communication device found in ${availableDevices.size} available devices.")
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoAvailableOffCall) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    isScoActive = true
                    boostMicSensitivity(am)
                    Log.i(TAG, "🎧 Started legacy Bluetooth SCO for microphone capture")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing to Bluetooth headset", e)
        }
        return false
    }

    /**
     * Checks if a Bluetooth headset/earbuds is currently connected and usable.
     */
    fun isBluetoothHeadsetConnected(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices.any { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
            } else {
                val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the name of the active Bluetooth audio device if connected.
     */
    fun getActiveBluetoothDeviceName(): String? {
        val am = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return am.communicationDevice?.productName?.toString()
                ?: am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }?.productName?.toString()
        }
        return null
    }

    /**
     * Boosts microphone input gain for low-voice earbud use.
     * When speaking quietly through earbuds, the SCO channel needs higher
     * sensitivity so SpeechRecognizer doesn't cut off or miss words.
     */
    private fun boostMicSensitivity(am: AudioManager) {
        try {
            // Max out the microphone input gain stream
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                maxVol,
                0 // no UI flag — silent boost
            )
            Log.d(TAG, "🎙️ Mic sensitivity boosted: voice_call stream set to max ($maxVol)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost mic sensitivity: ${e.message}")
        }
    }

    /**
     * Resets audio routing back to standard speaker/mic when speech is done.
     */
    fun resetToNormal() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
            am.mode = AudioManager.MODE_NORMAL
            isScoActive = false
            Log.d(TAG, "Reset audio routing to MODE_NORMAL")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audio routing", e)
        }
    }
}

