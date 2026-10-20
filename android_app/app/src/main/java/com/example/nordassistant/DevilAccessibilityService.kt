package com.example.nordassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * High-speed accessibility service that automatically performs hands-free actions:
 * 1. Automatically clicks "Send" in WhatsApp for messages & voice notes.
 * 2. Automatically performs Darwinbox Check-In & Check-Out:
 *    - Detects Darwinbox Dashboard and clicks "Check In" / "Clockin".
 *    - Detects CheckInRequestActivity and automatically clicks "Done" / "Submit".
 */
class DevilAccessibilityService : AccessibilityService() {

    enum class DarwinboxAction {
        NONE, CHECK_IN, CHECK_OUT
    }

    companion object {
        private const val TAG = "DevilAccessibility"
        var instance: DevilAccessibilityService? = null

        // WhatsApp auto-send state
        var isAutoSendPending = false
        var targetContactName: String? = null
        var autoSendTimestamp: Long = 0

        // Darwinbox auto-attendance state
        var pendingDarwinboxAction = DarwinboxAction.NONE
        var darwinboxTimestamp: Long = 0

        fun triggerAutoSend(contactName: String? = null) {
            isAutoSendPending = true
            targetContactName = contactName
            autoSendTimestamp = System.currentTimeMillis()
            Log.i(TAG, "Auto-send ARMED for contact: $contactName")
            instance?.scheduleSendAttempts()
        }

        fun triggerDarwinbox(action: DarwinboxAction = DarwinboxAction.CHECK_IN) {
            pendingDarwinboxAction = action
            darwinboxTimestamp = System.currentTimeMillis()
            Log.i(TAG, "Darwinbox auto-attendance ARMED for action: $action")
            instance?.scheduleDarwinboxAttempts()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "DevilAccessibilityService CONNECTED and ACTIVE")
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.packageNames = null // Listen across apps to enforce J.A.R.V.I.S. distraction shielding
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun scheduleSendAttempts() {
        val delays = listOf(300L, 600L, 1000L, 1500L, 2200L, 3000L, 4500L)
        for (delay in delays) {
            handler.postDelayed({
                if (isAutoSendPending) {
                    trySend()
                }
            }, delay)
        }
    }

    fun scheduleDarwinboxAttempts() {
        val delays = listOf(400L, 800L, 1400L, 2200L, 3200L, 4500L, 6000L, 8000L, 11000L)
        for (delay in delays) {
            handler.postDelayed({
                if (pendingDarwinboxAction != DarwinboxAction.NONE) {
                    tryDarwinboxAutomation()
                }
            }, delay)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""

        // 1. J.A.R.V.I.S. Protocol Distraction Shielding during professional hours
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            com.example.nordassistant.protocol.DevilProtocolManager.isAppRestricted(pkg)) {
            Log.w(TAG, "Shielding restricted distraction app under J.A.R.V.I.S. Protocol: $pkg")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. High-performance early exit: If no automations are armed and not Darwinbox, exit in 0ms!
        val isDarwinbox = pkg.contains("darwinbox", ignoreCase = true)
        if (!isAutoSendPending && pendingDarwinboxAction == DarwinboxAction.NONE && !isDarwinbox) {
            return
        }

        // 3. Package-Gated Execution: Never crawl unrelated app hierarchies
        if (isAutoSendPending && (pkg.contains("whatsapp", ignoreCase = true) || pkg.isEmpty())) {
            trySend()
        }
        if (pendingDarwinboxAction != DarwinboxAction.NONE && (isDarwinbox || pkg.isEmpty())) {
            tryDarwinboxAutomation()
        } else if (isDarwinbox) {
            observeManualDarwinboxAttendance(event)
        }
    }

    private fun observeManualDarwinboxAttendance(event: AccessibilityEvent?) {
        try {
            if (com.example.nordassistant.data.DarwinboxShiftTracker.isCheckedInToday(this)) {
                return
            }
            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val text = event.text.joinToString(" ").lowercase()
                val resName = event.source?.viewIdResourceName ?: ""
                if (resName.contains("buttonSubmit") || resName.contains("bottomDone") ||
                    text.contains("done") || text.contains("submit") || text.contains("check in")) {
                    Log.i(TAG, "🎯 Passive detection: User manually clicked check-in button in Darwinbox!")
                    com.example.nordassistant.data.DarwinboxShiftTracker.recordCheckIn(this)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in observeManualDarwinboxAttendance", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ── DARWINBOX AUTOMATION (CHECK-IN / CHECK-OUT -> HIT DONE) ─────────
    // ═════════════════════════════════════════════════════════════════
    @Synchronized
    private fun tryDarwinboxAutomation() {
        if (pendingDarwinboxAction == DarwinboxAction.NONE) return

        // Auto-expire after 25 seconds
        if (System.currentTimeMillis() - darwinboxTimestamp > 25000) {
            pendingDarwinboxAction = DarwinboxAction.NONE
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            // ── Step 2: On CheckInRequestActivity -> Hit "Done" / "Submit" button ──
            val submitButtons = rootNode.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.attendance:id/buttonSubmit")
            if (!submitButtons.isNullOrEmpty()) {
                for (btn in submitButtons) {
                    if (btn.isClickable || btn.isEnabled) {
                        Log.i(TAG, "Found Darwinbox Done / Submit button (buttonSubmit). Hitting Done now!")
                        val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            pendingDarwinboxAction = DarwinboxAction.NONE
                            Log.i(TAG, "DARWINBOX CHECK-IN / CHECK-OUT DONE HIT SUCCESSFULLY!")
                            return
                        }
                    }
                }
            }

            // Fallback for Done: bottomDone container
            val bottomDoneNodes = rootNode.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.attendance:id/bottomDone")
            if (!bottomDoneNodes.isNullOrEmpty()) {
                for (bNode in bottomDoneNodes) {
                    if (bNode.isClickable) {
                        bNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingDarwinboxAction = DarwinboxAction.NONE
                        Log.i(TAG, "Clicked bottomDone container!")
                        return
                    }
                }
            }

            // Fallback by Text: "Done" or "Submit" on Check-In screen
            val doneTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByTextCaseInsensitive(rootNode, "done", doneTextNodes)
            findNodesByTextCaseInsensitive(rootNode, "submit", doneTextNodes)
            for (node in doneTextNodes) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    Log.i(TAG, "Found Done button by text. Clicking now!")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingDarwinboxAction = DarwinboxAction.NONE
                    return
                }
            }

            // ── Step 1: On Dashboard -> Click "Check In" / "Check Out" shortcut ──
            val checkInShortcutNodes = rootNode.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox:id/checkInShortcut")
            if (!checkInShortcutNodes.isNullOrEmpty()) {
                for (node in checkInShortcutNodes) {
                    if (node.isClickable || node.isEnabled) {
                        Log.i(TAG, "Found checkInShortcut on Dashboard. Clicking now!")
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return // Wait for CheckInRequestActivity to open
                    }
                }
            }

            // Fallback for Dashboard by text: "Check In", "Click here to Check In", "Clockin"
            val targetDashboardText = if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) "Clockout" else "Check In"
            val dashboardNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByTextCaseInsensitive(rootNode, targetDashboardText, dashboardNodes)
            for (node in dashboardNodes) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    Log.i(TAG, "Clicking $targetDashboardText shortcut by text search!")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryDarwinboxAutomation", e)
        }
    }

    private fun findNodesByTextCaseInsensitive(
        root: AccessibilityNodeInfo,
        targetText: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = root.text?.toString()
        if (nodeText != null && nodeText.contains(targetText, ignoreCase = true)) {
            results.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesByTextCaseInsensitive(child, targetText, results)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ── WHATSAPP HANDS-FREE DISPATCH ──────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════
    @Synchronized
    private fun trySend() {
        if (!isAutoSendPending) return

        // Auto-expire after 15 seconds to prevent unintended clicks later
        if (System.currentTimeMillis() - autoSendTimestamp > 15000) {
            isAutoSendPending = false
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            // ── Path 1: Direct Send Button (Active Chat or Media Preview) ──
            val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (!sendNodes.isNullOrEmpty()) {
                for (node in sendNodes) {
                    if (node.isClickable || node.isEnabled) {
                        Log.i(TAG, "Found WhatsApp send button (com.whatsapp:id/send). Clicking now!")
                        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) {
                            isAutoSendPending = false
                            Log.i(TAG, "AUTO-SEND COMPLETED SUCCESSFULLY via com.whatsapp:id/send")
                            return
                        }
                    }
                }
            }

            // Path 1b: Search by Content Description "Send"
            val descNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByDescription(rootNode, "Send", descNodes)
            for (node in descNodes) {
                if (node.isClickable || node.isEnabled) {
                    Log.i(TAG, "Found Send button by description 'Send'. Clicking now!")
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        isAutoSendPending = false
                        Log.i(TAG, "AUTO-SEND COMPLETED SUCCESSFULLY via description")
                        return
                    }
                }
            }

            // ── Path 2: Contact Picker (ExternalShareAlias) for Voice Notes ──
            val target = targetContactName
            if (!target.isNullOrBlank()) {
                val contactNodes = rootNode.findAccessibilityNodeInfosByText(target)
                if (!contactNodes.isNullOrEmpty()) {
                    for (cNode in contactNodes) {
                        var clickableRow: AccessibilityNodeInfo? = cNode
                        while (clickableRow != null && !clickableRow.isClickable) {
                            clickableRow = clickableRow.parent
                        }
                        if (clickableRow != null && clickableRow.isClickable) {
                            Log.i(TAG, "Selecting contact row for '$target' in picker")
                            clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                    }
                }
            }

            // Click Forward / Next FAB in contact picker
            val nextNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/next_btn")
            val fabNodes = if (nextNodes.isNullOrEmpty()) {
                rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/fab")
            } else nextNodes

            if (!fabNodes.isNullOrEmpty()) {
                for (fab in fabNodes) {
                    if (fab.isClickable || fab.isEnabled) {
                        Log.i(TAG, "Clicking next FAB button in WhatsApp picker")
                        fab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in trySend", e)
        }
    }

    private fun findNodesByDescription(
        root: AccessibilityNodeInfo,
        targetDesc: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val desc = root.contentDescription?.toString()
        if (desc != null && desc.equals(targetDesc, ignoreCase = true)) {
            results.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesByDescription(child, targetDesc, results)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DevilAccessibilityService interrupted")
    }
}
