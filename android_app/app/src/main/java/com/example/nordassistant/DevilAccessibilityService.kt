package com.example.nordassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.content.Context
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
 *    - Detects Darwinbox Dashboard and clicks "Check In" / "Check Out" shortcut card.
 *    - Detects CheckInRequestActivity and automatically clicks "Done" / "Submit".
 *    - Reads any error/geolocation dialog aloud, dismisses it, then STOPS.
 *    - No retry loops, no auto-reopen, no watchdog.
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
        // Set true once Done button is physically pressed; prevents re-clicking
        var isSubmitPressed: Boolean = false
        var submitPressTimeMs: Long = 0
        var lastDashboardClickTimeMs: Long = 0
        // Rate-limit the geolocation/auth warning speech
        var lastAuthWarningMs: Long = 0
        var lastGeofenceWarningMs: Long = 0

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
            isSubmitPressed = false
            submitPressTimeMs = 0
            lastDashboardClickTimeMs = 0
            lastAuthWarningMs = 0
            lastGeofenceWarningMs = 0
            val actionStr = when (action) {
                DarwinboxAction.CHECK_IN -> com.example.nordassistant.data.DarwinboxShiftTracker.PENDING_ACTION_CHECK_IN
                DarwinboxAction.CHECK_OUT -> com.example.nordassistant.data.DarwinboxShiftTracker.PENDING_ACTION_CHECK_OUT
                else -> com.example.nordassistant.data.DarwinboxShiftTracker.PENDING_ACTION_NONE
            }
            instance?.let {
                com.example.nordassistant.data.DarwinboxShiftTracker.savePendingAction(it, actionStr)
            }
            Log.i(TAG, "Darwinbox auto-attendance ARMED for action: $action")
            instance?.scheduleDarwinboxAttempts()
        }

        fun triggerYouTubeRefresh(): Boolean {
            val svc = instance ?: return false
            return svc.performYouTubeRefresh()
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
        val delays = listOf(300L, 600L, 1000L, 1500L, 2000L, 2600L, 3300L, 4200L, 5200L, 6500L, 8000L)
        for (delay in delays) {
            handler.postDelayed({
                if (isAutoSendPending) {
                    trySend()
                }
            }, delay)
        }
    }

    /**
     * Schedule fallback attempts to cover the cold-launch and dashboard loading window (up to ~18s).
     * Stops automatically once attendance action is completed or cancelled.
     */
    fun scheduleDarwinboxAttempts() {
        val delays = listOf(500L, 1500L, 3000L, 5000L, 7500L, 10500L, 14000L, 18000L)
        for (delay in delays) {
            handler.postDelayed({
                if (pendingDarwinboxAction != DarwinboxAction.NONE && !isSubmitPressed) {
                    tryDarwinboxAutomation()
                }
            }, delay)
        }
    }

    /**
     * Schedule follow-up checks after clicking the Dashboard shortcut card,
     * ensuring CheckInRequestActivity's Done button is detected and clicked immediately.
     */
    fun schedulePostDashboardChecks() {
        val delays = listOf(600L, 1200L, 2000L, 3000L, 4500L, 6500L, 9000L)
        for (delay in delays) {
            handler.postDelayed({
                if (pendingDarwinboxAction != DarwinboxAction.NONE && !isSubmitPressed) {
                    tryDarwinboxAutomation()
                }
            }, delay)
        }
    }

    fun performYouTubeRefresh(): Boolean {
        Log.i(TAG, "Triggering YouTube pull-to-refresh gesture & reload...")
        return try {
            val metrics = resources.displayMetrics
            val startX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.28f
            val endY = metrics.heightPixels * 0.72f

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 350)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            // Also attempt to click Home tab if present
            try {
                val root = rootInActiveWindow
                val homeNodes = root?.findAccessibilityNodeInfosByText("Home")
                val homeButton = homeNodes?.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                if (homeButton != null) {
                    if (homeButton.isClickable) homeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else homeButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked YouTube Home tab to reload feed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not find/click YouTube Home tab", e)
            }

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "YouTube pull-to-refresh swipe completed successfully")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "YouTube pull-to-refresh swipe cancelled")
                }
            }, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch YouTube refresh gesture", e)
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""
        val eventType = event?.eventType ?: 0

        // 1. J.A.R.V.I.S. Protocol Distraction Shielding during professional hours
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            com.example.nordassistant.protocol.DevilProtocolManager.isAppRestricted(pkg)) {
            Log.w(TAG, "Shielding restricted distraction app under J.A.R.V.I.S. Protocol: $pkg")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. High-performance early exit: If no automations are armed and not Darwinbox, exit in 0ms!
        val isDarwinbox = pkg.contains("darwinbox", ignoreCase = true)

        // Restore pending action from disk if in-memory state was reset (e.g. service restarted)
        if (pendingDarwinboxAction == DarwinboxAction.NONE) {
            val savedAction = com.example.nordassistant.data.DarwinboxShiftTracker.getPendingAction(this)
            if (savedAction == com.example.nordassistant.data.DarwinboxShiftTracker.PENDING_ACTION_CHECK_IN) {
                pendingDarwinboxAction = DarwinboxAction.CHECK_IN
                darwinboxTimestamp = System.currentTimeMillis()
            } else if (savedAction == com.example.nordassistant.data.DarwinboxShiftTracker.PENDING_ACTION_CHECK_OUT) {
                pendingDarwinboxAction = DarwinboxAction.CHECK_OUT
                darwinboxTimestamp = System.currentTimeMillis()
            }
        }

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

    // ═════════════════════════════════════════════════════════════════════
    // ── DARWINBOX AUTOMATION (CHECK-IN / CHECK-OUT -> HIT DONE) ─────────
    //    Simple one-shot flow — NO retry loops, NO auto-reopen, NO watchdog
    // ═════════════════════════════════════════════════════════════════════
    @Synchronized
    private fun tryDarwinboxAutomation() {
        if (pendingDarwinboxAction == DarwinboxAction.NONE) return

        // Auto-expire after 45 seconds
        if (System.currentTimeMillis() - darwinboxTimestamp > 45000L) {
            Log.w(TAG, "Darwinbox automation timed out after 45 seconds")
            clearDarwinboxState()
            com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                this,
                "Darwinbox attendance timed out, Boss. Please check your phone."
            )
            return
        }

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString()?.contains("darwinbox", ignoreCase = true) == true) {
            rootNodes.add(activeRoot)
        }
        try {
            for (window in windows) {
                val wRoot = window.root ?: continue
                if (wRoot.packageName?.toString()?.contains("darwinbox", ignoreCase = true) == true) {
                    if (rootNodes.none { it == wRoot }) rootNodes.add(wRoot)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error collecting windows", e)
        }
        if (rootNodes.isEmpty()) {
            Log.d(TAG, "Darwinbox window not in active tree yet, waiting for next tick...")
            return
        }

        try {
            // ── Step 0a: Detect Auth / Sign-in / Biometric prompts ──
            val authKeywords = listOf(
                "sign in", "842157@au", "authentication required", "verify identity",
                "touch the fingerprint sensor", "enter password", "let's bring you back",
                "bring you back", "face id", "fingerprint"
            )
            for (rootNode in rootNodes) {
                val authNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTextOrDescription(rootNode, authKeywords, authNodes)
                if (authNodes.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastAuthWarningMs > 10_000L) {
                        lastAuthWarningMs = now
                        com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                            this,
                            "Boss, Darwinbox needs you to sign in with your fingerprint."
                        )
                    }
                    return
                }
            }

            // ── Step 0b: Detect Geolocation restriction or any Error Dialog → speak & STOP ──
            val errorKeywords = listOf(
                "geofencing restrictions",
                "not allowed from the selected location",
                "check in is not allowed",
                "check out is not allowed",
                "is not allowed from this location",
                "location not within",
                "failed to mark attendance",
                "unable to fetch location",
                "something went wrong",
                "server error"
            )
            val progressKeywords = listOf("please wait", "loading", "submitting", "processing", "fetching", "getting location")

            var detectedErrorMsg: String? = null

            // Check standard dialog view IDs first
            val dialogViewIds = listOf(
                "android:id/message",
                "com.darwinbox.darwinbox:id/textViewMessage",
                "com.darwinbox.darwinbox:id/dialogMessage",
                "com.darwinbox.darwinbox:id/message",
                "com.darwinbox.darwinbox:id/md_text_message",
                "com.darwinbox.darwinbox:id/tv_message",
                "com.darwinbox.darwinbox:id/alertTitle"
            )
            for (rootNode in rootNodes) {
                for (dId in dialogViewIds) {
                    val dNodes = rootNode.findAccessibilityNodeInfosByViewId(dId)
                    if (!dNodes.isNullOrEmpty()) {
                        val txt = dNodes[0].text?.toString()?.trim() ?: ""
                        if (txt.length > 8 &&
                            !txt.equals("Check In", ignoreCase = true) &&
                            !txt.equals("Done", ignoreCase = true) &&
                            progressKeywords.none { txt.contains(it, ignoreCase = true) }) {
                            detectedErrorMsg = txt
                            break
                        }
                    }
                }
                if (detectedErrorMsg != null) break
            }

            // Also scan for known error keywords in node text/desc
            if (detectedErrorMsg == null) {
                for (rootNode in rootNodes) {
                    val errorNodes = mutableListOf<AccessibilityNodeInfo>()
                    findNodesByTextOrDescription(rootNode, errorKeywords, errorNodes)
                    for (errNode in errorNodes) {
                        val text = (errNode.text ?: errNode.contentDescription ?: "").toString().trim()
                        if (text.isNotEmpty() && text.length > 8 &&
                            progressKeywords.none { text.contains(it, ignoreCase = true) }) {
                            detectedErrorMsg = text
                            break
                        }
                    }
                    if (detectedErrorMsg != null) break
                }
            }

            if (detectedErrorMsg != null) {
                Log.w(TAG, "Darwinbox Error Detected: '$detectedErrorMsg'")
                val now = System.currentTimeMillis()
                if (now - lastGeofenceWarningMs > 4000L) {
                    lastGeofenceWarningMs = now
                    com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                        this,
                        "Darwinbox says: $detectedErrorMsg, Boss."
                    )
                }
                // Dismiss the dialog by tapping OK/Dismiss
                for (rootNode in rootNodes) {
                    val okNodes = mutableListOf<AccessibilityNodeInfo>()
                    findNodesByTextOrDescription(rootNode, listOf("ok", "okay", "dismiss", "got it", "close"), okNodes)
                    for (okNode in okNodes) {
                        if (clickNodeOrCoordinates(okNode)) break
                    }
                }
                // STOP — do not retry, and do not record false attendance
                clearDarwinboxState()
                return
            }

            // ── Step 0c: Post-submit response handler ──
            if (isSubmitPressed) {
                val elapsedSinceSubmit = System.currentTimeMillis() - submitPressTimeMs
                if (elapsedSinceSubmit > 3500L) {
                    Log.i(TAG, "✅ No error dialog detected after submit. Recording attendance!")
                    if (pendingDarwinboxAction == DarwinboxAction.CHECK_IN) {
                        val info = com.example.nordassistant.data.DarwinboxShiftTracker.recordCheckIn(this)
                        com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                            this,
                            "Check-in submitted on Darwinbox, Boss."
                        )
                        Log.i(TAG, "Check-in recorded at ${info.checkInTimeStr}")
                    } else if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) {
                        val checkoutStr = com.example.nordassistant.data.DarwinboxShiftTracker.recordCheckOut(this)
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        notificationManager?.cancel(com.example.nordassistant.data.DarwinboxAlarmReceiver.NOTIFICATION_ID)
                        com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                            this,
                            "Check-out submitted on Darwinbox, Boss."
                        )
                        Log.i(TAG, "Check-out recorded at $checkoutStr")
                    }
                    clearDarwinboxState()
                } else {
                    Log.d(TAG, "Waiting for Darwinbox server response (${elapsedSinceSubmit}ms elapsed)...")
                }
                return
            }

            // ── Step 1: Check if we're on the Check In / Submission Screen → Click Done ──
            var classicSubmitButton: AccessibilityNodeInfo? = null
            var genericSubmitButton: AccessibilityNodeInfo? = null
            var isCheckInScreen = false

            for (rootNode in rootNodes) {
                // Classic Darwinbox: buttonSubmit in CheckInRequestActivity
                val classicButtons = rootNode.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.attendance:id/buttonSubmit")
                if (!classicButtons.isNullOrEmpty()) {
                    classicSubmitButton = classicButtons[0]
                    isCheckInScreen = true
                }
                // Fallback: any visible Submit/Done button near bottom of screen
                if (classicSubmitButton == null) {
                    val submitButtons = mutableListOf<AccessibilityNodeInfo>()
                    findNodesByTextOrDescription(rootNode, listOf("submit", "done"), submitButtons)
                    for (sNode in submitButtons) {
                        val b = Rect()
                        sNode.getBoundsInScreen(b)
                        val label = (sNode.text ?: sNode.contentDescription ?: "").toString().trim().lowercase()
                        if (b.bottom > 1800 && (label == "done" || label == "submit" || label.contains("submit") || label.contains("done"))) {
                            genericSubmitButton = sNode
                            isCheckInScreen = true
                            break
                        }
                    }
                }
                // Check-in screen indicators (workstation field, etc.)
                val checkInIndicators = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTextOrDescription(rootNode, listOf("work station", "current location", "change location", "type your message"), checkInIndicators)
                if (checkInIndicators.isNotEmpty()) isCheckInScreen = true
            }

            if (isCheckInScreen) {
                // Check if GPS location has finished resolving in editTextLocation
                var isLocationReady = true
                for (rootNode in rootNodes) {
                    val locNodes = rootNode.findAccessibilityNodeInfosByViewId("com.darwinbox.darwinbox.attendance:id/editTextLocation")
                    if (!locNodes.isNullOrEmpty()) {
                        val locText = locNodes[0].text?.toString()?.trim() ?: ""
                        if (locText.isEmpty() || locText.contains("fetching", ignoreCase = true) || locText.contains("getting", ignoreCase = true) || locText.contains("loading", ignoreCase = true)) {
                            isLocationReady = false
                            break
                        }
                    }
                }
                if (!isLocationReady) {
                    Log.d(TAG, "Darwinbox is still resolving GPS location, waiting for next tick...")
                    return
                }

                // If submit button is not found yet and we just transitioned to this screen, wait for layout
                if (classicSubmitButton == null && genericSubmitButton == null) {
                    Log.d(TAG, "Submission screen detected but Done button is not yet laid out, waiting for next tick...")
                    return
                }

                Log.i(TAG, "🎯 On Darwinbox submission screen. Clicking Done/Submit NOW...")
                isSubmitPressed = true
                submitPressTimeMs = System.currentTimeMillis()

                val targetButton = classicSubmitButton ?: genericSubmitButton
                if (targetButton != null) {
                    val clicked = clickNodeOrCoordinates(targetButton)
                    Log.i(TAG, "✅ Done button clicked (result: $clicked)")
                } else {
                    // Hardware fallback: tap at known Done button coordinate
                    val path = Path().apply { moveTo(540f, 2271f) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                        .build()
                    dispatchGesture(gesture, null, null)
                    Log.i(TAG, "✅ Done button hardware-tapped at (540, 2271) as fallback")
                }

                com.example.nordassistant.data.DevilVoiceSpeaker.speak(
                    this,
                    "Done button pressed on Darwinbox, Boss. Verifying attendance..."
                )

                // Schedule post-submit verification ticks
                val postSubmitDelays = listOf(1000L, 2000L, 3500L, 5000L)
                for (delay in postSubmitDelays) {
                    handler.postDelayed({
                        if (pendingDarwinboxAction != DarwinboxAction.NONE) {
                            tryDarwinboxAutomation()
                        }
                    }, delay)
                }
                return
            }

            // ── Step 2: On Dashboard → Click the Check-In / Check-Out shortcut card ──
            // If we clicked the card less than 3 seconds ago, give CheckInRequestActivity time to open
            if (System.currentTimeMillis() - lastDashboardClickTimeMs < 3000L) {
                Log.d(TAG, "Waiting for CheckInRequestActivity to finish loading after dashboard card click...")
                return
            }

            val shortcutViewIds = if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) {
                listOf(
                    "com.darwinbox.darwinbox:id/checkOutShortcut",
                    "com.darwinbox.darwinbox:id/buttonCheckOut",
                    "com.darwinbox.darwinbox:id/textViewCheckOut",
                    "com.darwinbox.darwinbox:id/textViewCheckOutTitle",
                    "com.darwinbox.darwinbox:id/textViewCheckOutDesc",
                    "com.darwinbox.darwinbox:id/checkInShortcut",
                    "com.darwinbox.darwinbox:id/attendanceShortcut",
                    "com.darwinbox.darwinbox.attendance:id/checkOutShortcut",
                    "com.darwinbox.darwinbox.attendance:id/checkInShortcut"
                )
            } else {
                listOf(
                    "com.darwinbox.darwinbox:id/checkInShortcut",
                    "com.darwinbox.darwinbox:id/buttonCheckIn",
                    "com.darwinbox.darwinbox:id/textViewCheckInTitle",
                    "com.darwinbox.darwinbox:id/textViewCheckInDesc",
                    "com.darwinbox.darwinbox:id/attendanceShortcut",
                    "com.darwinbox.darwinbox.attendance:id/checkInShortcut"
                )
            }

            for (rootNode in rootNodes) {
                for (viewId in shortcutViewIds) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            Log.i(TAG, "Found Darwinbox Dashboard Shortcut ($viewId). Clicking...")
                            if (clickNodeOrCoordinates(node)) {
                                lastDashboardClickTimeMs = System.currentTimeMillis()
                                Log.i(TAG, "Clicked $viewId — scheduling follow-up checks for submission screen")
                                schedulePostDashboardChecks()
                                return
                            }
                        }
                    }
                }
            }

            // Fallback: find dashboard card by text
            val dashboardKeywords = if (pendingDarwinboxAction == DarwinboxAction.CHECK_OUT) {
                listOf("check out", "checkout", "check-out", "clock out", "clockout")
            } else {
                listOf("lets get to work", "check in", "checkin", "check-in", "clock in", "clockin")
            }
            val excludePatterns = listOf("fingerprint", "biometric", "verify", "face id", "face recognition", "authenticat")

            for (rootNode in rootNodes) {
                val dashboardNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTextOrDescription(rootNode, dashboardKeywords, dashboardNodes)
                for (node in dashboardNodes) {
                    val nodeLabel = (node.text ?: node.contentDescription ?: "").toString().lowercase()
                    if (excludePatterns.any { nodeLabel.contains(it) }) continue
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    // Only click large cards (not tiny labels)
                    if (bounds.width() > 400 || nodeLabel.contains("lets get to work")) {
                        Log.i(TAG, "Found dashboard card by text: '$nodeLabel'. Clicking...")
                        if (clickNodeOrCoordinates(node)) {
                            lastDashboardClickTimeMs = System.currentTimeMillis()
                            Log.i(TAG, "Clicked dashboard card by text '$nodeLabel' — scheduling follow-up checks")
                            schedulePostDashboardChecks()
                            return
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in tryDarwinboxAutomation", e)
        }
    }

    private fun clearDarwinboxState() {
        pendingDarwinboxAction = DarwinboxAction.NONE
        isSubmitPressed = false
        submitPressTimeMs = 0
        lastDashboardClickTimeMs = 0
        com.example.nordassistant.data.DarwinboxShiftTracker.clearPendingAction(this)
    }

    private fun clickNodeOrCoordinates(node: AccessibilityNodeInfo): Boolean {
        // Physical click simulation via dispatchGesture at center of view bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        var dispatched = false
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val clickX = bounds.centerX().toFloat()
            val clickY = bounds.centerY().toFloat()
            Log.i(TAG, "Dispatching hardware touch gesture at ($clickX, $clickY) bounds: $bounds")
            val path = Path().apply { moveTo(clickX, clickY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Hardware touch gesture COMPLETED at ($clickX, $clickY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Hardware touch gesture CANCELLED at ($clickX, $clickY)")
                }
            }, null)
        }
        // Also fire performAction as backup
        val actionClicked = (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                (node.parent?.isClickable == true && node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        return dispatched || actionClicked
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

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) rootNodes.add(activeRoot)
        try {
            for (window in windows) {
                val r = window.root
                if (r != null && rootNodes.none { it == r }) {
                    rootNodes.add(r)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error collecting windows", e)
        }
        if (rootNodes.isEmpty()) return

        try {
            // ── Path 1: Direct Send Button (Active Chat or Media Preview) ──
            val sendViewIds = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp:id/send_container",
                "com.whatsapp:id/conversation_entry_action_button"
            )
            for (rootNode in rootNodes) {
                for (vId in sendViewIds) {
                    val sendNodes = rootNode.findAccessibilityNodeInfosByViewId(vId)
                    if (!sendNodes.isNullOrEmpty()) {
                        for (node in sendNodes) {
                            Log.i(TAG, "Found WhatsApp send button via $vId. Clicking now!")
                            if (clickSendButton(node)) {
                                isAutoSendPending = false
                                Log.i(TAG, "AUTO-SEND COMPLETED SUCCESSFULLY via $vId")
                                return
                            }
                        }
                    }
                }

                // Path 1b: Search by Content Description "Send"
                val descNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByDescription(rootNode, "Send", descNodes)
                for (node in descNodes) {
                    Log.i(TAG, "Found Send button by description 'Send'. Clicking now!")
                    if (clickSendButton(node)) {
                        isAutoSendPending = false
                        Log.i(TAG, "AUTO-SEND COMPLETED SUCCESSFULLY via description")
                        return
                    }
                }
            }

            // ── Path 2: Contact Picker (ExternalShareAlias) for Voice Notes ──
            val target = targetContactName
            for (rootNode in rootNodes) {
                if (!target.isNullOrBlank()) {
                    val contactNodes = rootNode.findAccessibilityNodeInfosByText(target)
                    if (!contactNodes.isNullOrEmpty()) {
                        for (cNode in contactNodes) {
                            var clickableRow: AccessibilityNodeInfo? = cNode
                            while (clickableRow != null && !clickableRow.isClickable) {
                                clickableRow = clickableRow.parent
                            }
                            if (clickableRow != null) {
                                Log.i(TAG, "Selecting contact row for '$target' in picker")
                                if (clickNodeOrCoordinates(clickableRow)) {
                                    break
                                }
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
                        Log.i(TAG, "Clicking next FAB button in WhatsApp picker")
                        if (clickNodeOrCoordinates(fab)) {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in trySend", e)
        }
    }

    private fun clickSendButton(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        var dispatched = false
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val clickX = bounds.centerX().toFloat()
            val clickY = bounds.centerY().toFloat()
            Log.i(TAG, "Dispatching hardware touch gesture on Send button at ($clickX, $clickY)")
            val path = Path().apply { moveTo(clickX, clickY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Hardware touch gesture on Send COMPLETED at ($clickX, $clickY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Hardware touch gesture on Send CANCELLED at ($clickX, $clickY)")
                }
            }, null)
        }
        // Also fire performAction as backup
        val actionClicked = (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                (node.parent?.isClickable == true && node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        return dispatched || actionClicked
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
