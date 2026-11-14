package com.example.nordassistant.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Hardware and Device controls:
 * 1. Flashlight / Torch (CameraManager)
 * 2. Wi-Fi / Internet Settings & Panel
 * 3. Bluetooth Settings & Panel
 * 4. Battery Level & Status
 * 5. Media Volume Control
 */
object DeviceControlHelper {
    private const val TAG = "DeviceControlHelper"

    private var isTorchOn = false
    private var torchCallbackRegistered = false

    private fun ensureTorchCallback(context: Context) {
        if (torchCallbackRegistered) return
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    super.onTorchModeChanged(cameraId, enabled)
                    isTorchOn = enabled
                    Log.d(TAG, "Hardware torch state updated: isTorchOn=$enabled (camera $cameraId)")
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    super.onTorchModeUnavailable(cameraId)
                    Log.w(TAG, "Hardware torch unavailable on camera $cameraId")
                }
            }, null)
            torchCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register torch callback", e)
        }
    }

    /**
     * Controls the device flashlight.
     * @param enable true to turn on, false to turn off, null to toggle current state.
     * @return Pair of (success boolean, spoken message string)
     */
    fun setFlashlight(context: Context, enable: Boolean? = null): Pair<Boolean, String> {
        ensureTorchCallback(context)
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                return false to "No camera flash found on this phone, Boss."
            }

            val targetState = enable ?: !isTorchOn
            cameraManager.setTorchMode(cameraId, targetState)
            isTorchOn = targetState

            if (targetState) {
                true to "Flashlight turned on, Boss."
            } else {
                true to "Flashlight turned off, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode", e)
            false to "Sorry Boss, I couldn't control the flashlight: ${e.message}"
        }
    }

    /**
     * Opens the Internet / Wi-Fi floating panel or settings.
     */
    fun openInternetPanel(context: Context): String {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent("android.settings.panel.action.INTERNET").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            "Opening Internet settings, Boss."
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_INTERNET failed, trying ACTION_WIFI_SETTINGS", e)
            try {
                val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                "Opening Wi-Fi settings, Boss."
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open Wi-Fi settings", e2)
                "Could not open network settings on your phone, Boss."
            }
        }
    }

    /**
     * Returns the current Wi-Fi / Internet connection status.
     */
    fun getInternetStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)

            if (caps == null) {
                return "You are currently offline with no active internet connection, Boss."
            }

            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            if (isWifi) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val connectionInfo: WifiInfo? = wm?.connectionInfo
                val ssid = connectionInfo?.ssid?.replace("\"", "") ?: ""
                val networkName = if (ssid.isNotEmpty() && ssid != "<unknown ssid>") "to $ssid" else ""
                "You are connected to Wi-Fi $networkName, Boss."
            } else if (isCellular) {
                "You are connected via Mobile Data, Boss."
            } else {
                "Internet is connected, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet status", e)
            "Could not check internet status, Boss."
        }
    }

    /**
     * Opens the Bluetooth floating panel or settings.
     */
    fun openBluetoothPanel(context: Context): String {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent("android.settings.panel.action.BLUETOOTH").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            "Opening Bluetooth settings, Boss."
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_BLUETOOTH failed, falling back to ACTION_BLUETOOTH_SETTINGS", e)
            try {
                val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                "Opening Bluetooth settings, Boss."
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open Bluetooth settings", e2)
                "Could not open Bluetooth settings, Boss."
            }
        }
    }

    /**
     * Returns the current battery status and percentage.
     */
    fun getBatteryStatus(context: Context): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            if (pct >= 0) {
                val chargingText = if (isCharging) "and currently charging" else ""
                "Your battery is at $pct% $chargingText, Boss."
            } else {
                "Could not read battery level, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading battery status", e)
            "Could not retrieve battery level, Boss."
        }
    }

    /**
     * Volume controls for media stream.
     */
    fun adjustVolume(context: Context, direction: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = (current * 100) / max
            if (direction == AudioManager.ADJUST_RAISE) {
                "Volume increased to $pct%, Boss."
            } else {
                "Volume decreased to $pct%, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume", e)
            "Could not adjust volume, Boss."
        }
    }

    fun setMaxVolume(context: Context): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
            "Media volume set to maximum, Boss."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting max volume", e)
            "Could not set max volume, Boss."
        }
    }

    fun setMuteVolume(context: Context, mute: Boolean): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                "Media volume muted, Boss."
            } else {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, AudioManager.FLAG_SHOW_UI)
                "Media volume unmuted, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting mute volume", e)
            "Could not mute volume, Boss."
        }
    }

    fun setVolumePercent(context: Context, percent: Int): String {
        return try {
            val clamped = percent.coerceIn(0, 100)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * clamped) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped%, Boss."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume percent", e)
            "Could not set volume, Boss."
        }
    }
}
