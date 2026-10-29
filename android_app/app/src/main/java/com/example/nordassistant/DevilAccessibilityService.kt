package com.example.nordassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
        val delays = listOf(300L, 700L, 1200L, 1800L, 2500L, 3400L, 4500L, 5800L, 7500L, 9500L, 12000L, 15000L, 18000L, 22000L)
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
            scrapeDarwinboxHolidaysIfPresent()
        }
    }

    private fun scrapeDarwinboxHolidaysIfPresent() {
        try {
            val root = rootInActiveWindow ?: return
            val holidayNameNodes = root.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.leave:id/textViewHolidayName")
            if (holidayNameNodes.isNullOrEmpty()) return

            val monthNodes = root.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.leave:id/textViewMonth")

            for (nameNode in holidayNameNodes) {
                val holidayName = nameNode.text?.toString()?.trim() ?: continue
                if (holidayName.isEmpty()) continue

                val parent = nameNode.parent ?: continue
                val dateNodes = parent.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.leave:id/textViewDate")
                val dateStr = dateNodes.firstOrNull()?.text?.toString()?.trim() ?: continue
                val day = dateStr.toIntOrNull() ?: continue

                // Find nearest month node positioned above this holiday node
                val nameBounds = Rect()
                nameNode.getBoundsInScreen(nameBounds)

                var bestMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
                var bestDist = Int.MAX_VALUE
                for (mNode in monthNodes) {
                    val mBounds = Rect()
                    mNode.getBoundsInScreen(mBounds)
                    if (mBounds.bottom <= nameBounds.top) {
                        val dist = nameBounds.top - mBounds.bottom
                        if (dist < bestDist) {
                            bestDist = dist
                            val mText = mNode.text?.toString() ?: ""
                            bestMonth = com.example.nordassistant.data.CorporateCalendarEngine.extractMonthFromQuery(mText)
                        }
                    }
                }

                com.example.nordassistant.data.CorporateCalendarEngine.registerCustomHoliday(this, holidayName, bestMonth, day)
                Log.i(TAG, "🎯 Auto-synced Darwinbox holiday from screen: $holidayName on Month $bestMonth Day $day")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scraping Darwinbox holidays", e)
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

        // Auto-expire after 28 seconds
        if (System.currentTimeMillis() - darwinboxTimestamp > 28000) {
            Log.w(TAG, "Darwinbox automation timed out after 28 seconds")
            pendingDarwinboxAction = DarwinboxAction.NONE
            return
        }

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { rootNodes.add(it) }
        try {
            for (window in windows) {
                window.root?.let { wRoot ->
                    if (rootNodes.none { it == wRoot }) {
                        rootNodes.add(wRoot)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error collecting windows", e)
        }
        if (rootNodes.isEmpty()) return

        try {
            // ── Auto-grant any system location / permission dialogs blocking Darwinbox ──
            val permissionViewIds = listOf(
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.android.permissioncontroller:id/permission_allow_button",
                "com.android.permissioncontroller:id/permission_allow_one_time_button"
            )
            for (rootNode in rootNodes) {
                for (pId in permissionViewIds) {
                    val pNodes = rootNode.findAccessibilityNodeInfosByViewId(pId)
                    if (!pNodes.isNullOrEmpty()) {
                        for (pNode in pNodes) {
                            Log.i(TAG, "Auto-granting Darwinbox permission via $pId")
                            clickNodeOrCoordinates(pNode)
                        }
                    }
                }
            }

            // ── Step 2: On CheckInRequestActivity / Dialog -> Hit "Done" / "Submit" / "Clock Out" confirmation ──
            val submitViewIds = listOf(
                "com.darwinbox.darwinbox:id/buttonClockInOut",
                "com.darwinbox.darwinbox.attendance:id/buttonClockInOut",
                "com.darwinbox.darwinbox.attendance:id/buttonSubmit",
                "com.darwinbox.darwinbox:id/buttonSubmit",
                "com.darwinbox.darwinbox.attendance:id/bottomDone",
                "com.darwinbox.darwinbox:id/bottomDone",
                "com.darwinbox.darwinbox.attendance:id/btn_submit",
                "com.darwinbox.darwinbox:id/btn_submit",
                "com.darwinbox.darwinbox.attendance:id/btnSubmit",
                "com.darwinbox.darwinbox:id/btnSubmit",
                "com.darwinbox.darwinbox.attendance:id/btnDone",
                "com.darwinbox.darwinbox:id/btnDone",
                "com.darwinbox.darwinbox:id/action_done",
                "com.darwinbox.darwinbox.attendance:id/action_done",
                "com.darwinbox.darwinbox:id/ivSubmit",
                "com.darwinbox.darwinbox.attendance:id/ivSubmit",
                "com.darwinbox.darwinbox:id/btnSubmitPulseActivity",
                "com.darwinbox.darwinbox:id/btn_attending"
            )

            for (rootNode in rootNodes) {
                for (viewId in submitViewIds) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            Log.i(TAG, "Found Darwinbox Submit/Done button by view ID ($viewId). Clicking now!")
                            if (clickNodeOrCoordinates(node)) {
                                pendingDarwinboxAction = DarwinboxAction.NONE
                                Log.i(TAG, "DARWINBOX CHECK-IN / CHECK-OUT DONE HIT SUCCESSFULLY via ID $viewId!")
                                return
                            }
                        }
                    }
                }
            }

            // Fallback for Done / Submit by Text & ContentDescription on dialog/confirmation screen
            val submitKeywords = listOf(
                "submit", "done", "confirm", "proceed", "save", "mark attendance"
            )
            for (rootNode in rootNodes) {
                val doneCandidateNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTextOrDescription(rootNode, submitKeywords, doneCandidateNodes)
                for (node in doneCandidateNodes) {
                    Log.i(TAG, "Found Darwinbox Done / Submit candidate by text: '${node.text ?: node.contentDescription}'")
                    if (clickNodeOrCoordinates(node)) {
                        pendingDarwinboxAction = DarwinboxAction.NONE
                        Log.i(TAG, "DARWINBOX SUBMIT / DONE HIT SUCCESSFULLY via text/description!")
                        return
                    }
                }
            }

            // ── Step 1: On Dashboard -> Click "Check In" / "Check Out" shortcut ──
            val shortcutViewIds = if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) {
                listOf(
                    "com.darwinbox.darwinbox:id/checkOutShortcut",
                    "com.darwinbox.darwinbox:id/buttonCheckOut",
                    "com.darwinbox.darwinbox:id/textViewCheckOut",
                    "com.darwinbox.darwinbox:id/checkInShortcut",
                    "com.darwinbox.darwinbox:id/textViewCheckInTitle",
                    "com.darwinbox.darwinbox:id/textViewCheckInDesc",
                    "com.darwinbox.darwinbox:id/attendanceShortcut",
                    "com.darwinbox.darwinbox:id/btn_checkout",
                    "com.darwinbox.darwinbox:id/action_checkout",
                    "com.darwinbox.darwinbox:id/iv_checkout",
                    "com.darwinbox.darwinbox:id/tv_checkout",
                    "com.darwinbox.darwinbox:id/textViewClockOutTime",
                    "com.darwinbox.darwinbox:id/layoutInTimeOutTime",
                    "com.darwinbox.darwinbox.attendance:id/checkOutShortcut",
                    "com.darwinbox.darwinbox.attendance:id/checkInShortcut"
                )
            } else {
                listOf(
                    "com.darwinbox.darwinbox:id/checkInShortcut",
                    "com.darwinbox.darwinbox:id/buttonCheckIn",
                    "com.darwinbox.darwinbox:id/btn_check_in",
                    "com.darwinbox.darwinbox:id/textViewCheckIn",
                    "com.darwinbox.darwinbox:id/textViewCheckInTitle",
                    "com.darwinbox.darwinbox:id/textViewCheckInDesc",
                    "com.darwinbox.darwinbox:id/attendanceShortcut",
                    "com.darwinbox.darwinbox:id/btn_checkin",
                    "com.darwinbox.darwinbox:id/action_checkin",
                    "com.darwinbox.darwinbox:id/iv_checkin",
                    "com.darwinbox.darwinbox:id/tv_checkin",
                    "com.darwinbox.darwinbox:id/textViewClockInTime",
                    "com.darwinbox.darwinbox:id/layoutInTimeOutTime",
                    "com.darwinbox.darwinbox.attendance:id/checkInShortcut"
                )
            }

            for (rootNode in rootNodes) {
                for (viewId in shortcutViewIds) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            Log.i(TAG, "Found Darwinbox Dashboard Shortcut by ID ($viewId). Clicking now!")
                            if (clickNodeOrCoordinates(node)) {
                                Log.i(TAG, "Clicked Dashboard Shortcut ($viewId), awaiting submission screen...")
                                return
                            }
                        }
                    }
                }
            }

            // Fallback for Dashboard by text & content description
            // IMPORTANT: Avoid broad keywords like "in"/"out" — they false-match "Verify with fingerprint" etc.
            val dashboardKeywords = if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) {
                listOf(
                    "check out", "checkout", "check-out", "clock out", "clockout", "clock-out",
                    "punch out", "punchout", "tap to check out", "click here to check out",
                    "check out now", "mark out", "swipe out"
                )
            } else {
                listOf(
                    "check in", "checkin", "check-in", "clock in", "clockin", "clock-in",
                    "punch in", "punchin", "tap to check in", "click here to check in",
                    "check in now", "mark in", "swipe in"
                )
            }

            // Filter out nodes that contain "fingerprint", "biometric", "verify", or "face" to avoid false clicks
            val excludePatterns = listOf("fingerprint", "biometric", "verify", "face id", "face recognition", "authenticat")

            for (rootNode in rootNodes) {
                val dashboardNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTextOrDescription(rootNode, dashboardKeywords, dashboardNodes)
                for (node in dashboardNodes) {
                    val nodeLabel = (node.text ?: node.contentDescription ?: "").toString().lowercase()
                    if (excludePatterns.any { nodeLabel.contains(it) }) {
                        Log.w(TAG, "Skipping false-match node: '$nodeLabel' (contains excluded pattern)")
                        continue
                    }
                    Log.i(TAG, "Found Darwinbox Dashboard button by text: '${node.text ?: node.contentDescription}'")
                    if (clickNodeOrCoordinates(node)) {
                        Log.i(TAG, "Clicked Dashboard shortcut by text search, awaiting submission screen...")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryDarwinboxAutomation", e)
        }
    }

    private fun clickNodeOrCoordinates(node: AccessibilityNodeInfo): Boolean {
        // Strategy A: Direct click on node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "Clicked node directly via performAction(ACTION_CLICK)")
            return true
        }

        // Strategy B: Traverse up to clickable parent
        var clickable: AccessibilityNodeInfo? = node.parent
        while (clickable != null) {
            if (clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked parent node via performAction(ACTION_CLICK)")
                return true
            }
            clickable = clickable.parent
        }

        // Strategy C: Physical click simulation via dispatchGesture at center of view bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val clickX = bounds.centerX().toFloat()
            val clickY = bounds.centerY().toFloat()
            Log.i(TAG, "Dispatching hardware touch gesture at ($clickX, $clickY) for bounds: $bounds")
            val path = Path().apply {
                moveTo(clickX, clickY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Hardware touch gesture COMPLETED at ($clickX, $clickY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Hardware touch gesture CANCELLED at ($clickX, $clickY)")
                }
            }, null)
            Log.i(TAG, "dispatchGesture result: $dispatched")
            return dispatched
        }

        return false
    }

    private fun findNodesByTextOrDescription(
        root: AccessibilityNodeInfo,
        targetKeywords: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = root.text?.toString()?.trim()
        val nodeDesc = root.contentDescription?.toString()?.trim()

        for (kw in targetKeywords) {
            if ((nodeText != null && nodeText.contains(kw, ignoreCase = true)) ||
                (nodeDesc != null && nodeDesc.contains(kw, ignoreCase = true))) {
                results.add(root)
                break
            }
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesByTextOrDescription(child, targetKeywords, results)
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
