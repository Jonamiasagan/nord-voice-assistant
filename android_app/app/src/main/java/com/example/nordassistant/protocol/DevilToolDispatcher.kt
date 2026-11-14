package com.example.nordassistant.protocol

import android.util.Log
import org.json.JSONObject

/**
 * Structured tool actions that Qwen2.5 can emit for complex or natural voice instructions.
 */
sealed class DevilToolAction {
    data class SendWhatsApp(val recipient: String, val message: String) : DevilToolAction()
    data class SwitchProtocol(val protocol: DevilProtocol) : DevilToolAction()
    object CheckShiftStatus : DevilToolAction()
    object CheckHolidays : DevilToolAction()
    data class PlayMedia(val query: String, val shuffle: Boolean = false) : DevilToolAction()
    data class Navigate(val destination: String) : DevilToolAction()
    data class StoreMemory(val key: String, val fact: String) : DevilToolAction()
    data class RecallMemory(val key: String) : DevilToolAction()
    data class ToggleFlashlight(val enable: Boolean?) : DevilToolAction()
    object OpenInternetSettings : DevilToolAction()
    object CheckWifiStatus : DevilToolAction()
    object OpenBluetoothSettings : DevilToolAction()
    object CheckBatteryStatus : DevilToolAction()
    data class ControlVolume(val volumeAction: String, val percent: Int? = null) : DevilToolAction()
}

/**
 * Robust JSON parser for LLM tool calling tags (<tool_call>{...}</tool_call>).
 */
object DevilToolDispatcher {
    private const val TAG = "DevilToolDispatcher"

    fun parseToolCall(response: String): DevilToolAction? {
        val trimmed = response.trim()
        when {
            trimmed.contains("TOOL_BATTERY") -> return DevilToolAction.CheckBatteryStatus
            trimmed.contains("TOOL_FLASHLIGHT_ON") -> return DevilToolAction.ToggleFlashlight(true)
            trimmed.contains("TOOL_FLASHLIGHT_OFF") -> return DevilToolAction.ToggleFlashlight(false)
            trimmed.contains("TOOL_WIFI") -> return DevilToolAction.CheckWifiStatus
            trimmed.contains("TOOL_VOLUME_UP") -> return DevilToolAction.ControlVolume("up")
            trimmed.contains("TOOL_VOLUME_DOWN") -> return DevilToolAction.ControlVolume("down")
        }

        val jsonStr = when {
            response.contains("<tool_call>") -> {
                Regex("(?s)<tool_call>\\s*(\\{.*?\\})\\s*(?:</tool_call>|$)").find(response)?.groupValues?.get(1)
            }
            response.trim().startsWith("{") && response.trim().endsWith("}") -> {
                response.trim()
            }
            else -> null
        } ?: return null

        return try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action").lowercase()
            when (action) {
                "whatsapp", "send_whatsapp" -> {
                    val recipient = json.optString("recipient").ifEmpty { json.optString("contact") }
                    val message = json.optString("message").ifEmpty { json.optString("text") }
                    if (recipient.isNotBlank() && message.isNotBlank()) {
                        DevilToolAction.SendWhatsApp(recipient, message)
                    } else null
                }
                "protocol", "switch_protocol" -> {
                    val mode = json.optString("mode").lowercase()
                    val targetProtocol = when {
                        mode.contains("barn") || mode.contains("meeting") || mode.contains("silent") -> DevilProtocol.BARN_DOOR
                        mode.contains("house") || mode.contains("party") || mode.contains("dev") -> DevilProtocol.HOUSE_PARTY
                        mode.contains("jarvis") || mode.contains("office") || mode.contains("work") -> DevilProtocol.JARVIS
                        mode.contains("friday") || mode.contains("companion") -> DevilProtocol.FRIDAY
                        mode.contains("edith") || mode.contains("tactical") || mode.contains("defense") -> DevilProtocol.EDITH
                        mode.contains("baby") || mode.contains("chill") || mode.contains("leisure") -> DevilProtocol.BABY_MONITOR
                        mode.contains("boss") || mode.contains("devil") || mode.contains("normal") -> DevilProtocol.BOSS_MODE
                        else -> null
                    }
                    if (targetProtocol != null) DevilToolAction.SwitchProtocol(targetProtocol) else null
                }
                "shift", "shift_status", "darwinbox" -> DevilToolAction.CheckShiftStatus
                "holiday", "holidays" -> DevilToolAction.CheckHolidays
                "music", "play_music", "vlc" -> {
                    val q = json.optString("query")
                    val shuffle = json.optBoolean("shuffle", false)
                    DevilToolAction.PlayMedia(q, shuffle)
                }
                "navigation", "navigate", "maps" -> {
                    val dest = json.optString("destination")
                    if (dest.isNotBlank()) DevilToolAction.Navigate(dest) else null
                }
                "remember", "store_memory" -> {
                    val key = json.optString("key")
                    val fact = json.optString("fact")
                    if (key.isNotBlank() && fact.isNotBlank()) DevilToolAction.StoreMemory(key, fact) else null
                }
                "recall", "recall_memory" -> {
                    val key = json.optString("key")
                    if (key.isNotBlank()) DevilToolAction.RecallMemory(key) else null
                }
                "flashlight", "torch" -> {
                    val stateStr = json.optString("state").lowercase()
                    val enable = if (json.has("enable")) {
                        json.optBoolean("enable")
                    } else if (stateStr == "on" || stateStr == "enable") {
                        true
                    } else if (stateStr == "off" || stateStr == "disable") {
                        false
                    } else null
                    DevilToolAction.ToggleFlashlight(enable)
                }
                "wifi", "internet", "network" -> {
                    val type = json.optString("type").lowercase()
                    if (type == "status" || json.optBoolean("status", false)) {
                        DevilToolAction.CheckWifiStatus
                    } else {
                        DevilToolAction.OpenInternetSettings
                    }
                }
                "bluetooth" -> DevilToolAction.OpenBluetoothSettings
                "battery" -> DevilToolAction.CheckBatteryStatus
                "volume" -> {
                    val level = json.optString("level").lowercase()
                    val pct = if (json.has("percent")) json.optInt("percent") else null
                    DevilToolAction.ControlVolume(level, pct)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool call JSON: $jsonStr", e)
            null
        }
    }
}

