package com.example.nordassistant.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Manages Darwinbox 9-hour shift tracking, checkout reminders,
 * and 10-minute overtime alerts.
 */
object DarwinboxShiftTracker {
    private const val TAG = "DarwinboxShiftTracker"
    private const val PREFS_NAME = "devil_darwinbox_shift_prefs"

    private const val KEY_CHECKIN_MS = "checkin_timestamp"
    private const val KEY_CHECKOUT_MS = "checkout_timestamp"
    private const val KEY_TARGET_CHECKOUT_MS = "target_checkout_ms"
    private const val KEY_ALERT_MS = "alert_timestamp"
    private const val KEY_IS_CHECKED_OUT = "is_checked_out"
    private const val KEY_SHIFT_DATE = "shift_date"

    const val SHIFT_DURATION_MS = 9 * 60 * 60 * 1000L          // 9 Hours
    const val OVERDUE_ALERT_DELAY_MS = 10 * 60 * 1000L        // 10 Minutes grace period
    const val ALARM_REQUEST_CODE = 9002
    const val DAILY_CHECK_REQUEST_CODE = 9010
    const val ACTION_DAILY_1030_AM_CHECK = "com.example.nordassistant.ACTION_DAILY_1030_AM_CHECK"
    const val ACTION_ALARM_OVERDUE = "com.example.nordassistant.ACTION_DARWINBOX_OVERDUE"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    }

    data class ShiftInfo(
        val checkInMs: Long,
        val targetCheckoutMs: Long,
        val alertMs: Long,
        val checkInTimeStr: String,
        val checkoutTimeStr: String,
        val alertTimeStr: String
    )

    /**
     * Records a check-in timestamp, calculates 9-hour checkout,
     * schedules the 10-minute overtime alarm, and logs to BossMemoryVault.
     */
    fun recordCheckIn(context: Context, timestamp: Long = System.currentTimeMillis()): ShiftInfo {
        val targetCheckout = timestamp + SHIFT_DURATION_MS
        val alertTime = targetCheckout + OVERDUE_ALERT_DELAY_MS
        val today = getTodayDateString()

        getPrefs(context).edit()
            .putLong(KEY_CHECKIN_MS, timestamp)
            .putLong(KEY_TARGET_CHECKOUT_MS, targetCheckout)
            .putLong(KEY_ALERT_MS, alertTime)
            .putBoolean(KEY_IS_CHECKED_OUT, false)
            .putString(KEY_SHIFT_DATE, today)
            .apply()

        scheduleCheckoutAlarm(context, alertTime)

        val checkInStr = formatTime(timestamp)
        val checkoutStr = formatTime(targetCheckout)
        val alertStr = formatTime(alertTime)

        // Store into persistent memory vault for cross-session knowledge
        try {
            val vault = BossMemoryVault.getInstance(context)
            vault.remember(
                "today checkin",
                "Checked in at $checkInStr. 9-hour shift checkout at $checkoutStr. Overtime alert set for $alertStr.",
                "routine"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error logging checkin to BossMemoryVault", e)
        }

        Log.i(TAG, "⏰ Shift recorded: In at $checkInStr -> Target checkout: $checkoutStr -> Overdue alert: $alertStr")

        return ShiftInfo(timestamp, targetCheckout, alertTime, checkInStr, checkoutStr, alertStr)
    }

    /**
     * Records a check-out timestamp, marks shift complete, and cancels alarms.
     */
    fun recordCheckOut(context: Context, timestamp: Long = System.currentTimeMillis()): String {
        getPrefs(context).edit()
            .putLong(KEY_CHECKOUT_MS, timestamp)
            .putBoolean(KEY_IS_CHECKED_OUT, true)
            .apply()

        cancelCheckoutAlarm(context)

        val checkOutStr = formatTime(timestamp)
        try {
            val vault = BossMemoryVault.getInstance(context)
            vault.remember(
                "today checkout",
                "Checked out at $checkOutStr.",
                "routine"
            )
        } catch (_: Exception) {}

        Log.i(TAG, "🏁 Shift checked out at $checkOutStr")
        return checkOutStr
    }

    fun isCheckedInToday(context: Context): Boolean {
        val prefs = getPrefs(context)
        val date = prefs.getString(KEY_SHIFT_DATE, null)
        val checkIn = prefs.getLong(KEY_CHECKIN_MS, 0L)
        return date == getTodayDateString() && checkIn > 0L
    }

    fun isCheckedOutToday(context: Context): Boolean {
        val prefs = getPrefs(context)
        val date = prefs.getString(KEY_SHIFT_DATE, null)
        val isOut = prefs.getBoolean(KEY_IS_CHECKED_OUT, false)
        return date == getTodayDateString() && isOut
    }

    /**
     * Returns a human-friendly spoken status of the shift and time remaining.
     */
    fun getShiftStatus(context: Context): String {
        val prefs = getPrefs(context)
        val shiftDate = prefs.getString(KEY_SHIFT_DATE, null)
        val today = getTodayDateString()

        if (shiftDate != today || !isCheckedInToday(context)) {
            return "No Darwinbox check-in has been recorded for today, Boss. Would you like me to clock you in now?"
        }

        val checkInMs = prefs.getLong(KEY_CHECKIN_MS, 0L)
        val targetCheckoutMs = prefs.getLong(KEY_TARGET_CHECKOUT_MS, 0L)
        val isCheckedOut = prefs.getBoolean(KEY_IS_CHECKED_OUT, false)
        val now = System.currentTimeMillis()

        val checkInStr = formatTime(checkInMs)
        val checkoutStr = formatTime(targetCheckoutMs)

        if (isCheckedOut) {
            val checkOutMs = prefs.getLong(KEY_CHECKOUT_MS, 0L)
            val checkOutStr = if (checkOutMs > 0L) formatTime(checkOutMs) else "earlier"
            return "You have already completed your shift today, Boss! Checked in at $checkInStr and checked out at $checkOutStr."
        }

        val diffMs = targetCheckoutMs - now
        return if (diffMs > 0) {
            val remainingHours = (diffMs / (1000 * 60 * 60)).toInt()
            val remainingMins = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()

            val timePart = when {
                remainingHours > 0 && remainingMins > 0 -> "$remainingHours hours and $remainingMins minutes"
                remainingHours > 0 -> "$remainingHours hours"
                else -> "$remainingMins minutes"
            }

            "You checked in at $checkInStr, Boss. You have $timePart remaining in your 9-hour shift. Your checkout is scheduled for $checkoutStr."
        } else {
            val overdueMins = ((-diffMs) / (1000 * 60)).toInt()
            "Boss, your 9-hour shift finished $overdueMins minutes ago at $checkoutStr! You have not checked out on Darwinbox yet. Shall I clock you out now?"
        }
    }

    /**
     * Answers: "What is the time to leave?" / "When can I leave?".
     */
    fun getTimeToLeave(context: Context): String {
        val prefs = getPrefs(context)
        val shiftDate = prefs.getString(KEY_SHIFT_DATE, null)
        val today = getTodayDateString()

        if (shiftDate != today || !isCheckedInToday(context)) {
            return "No Darwinbox check-in has been recorded for today, Boss. Would you like me to clock you in now?"
        }

        val targetCheckoutMs = prefs.getLong(KEY_TARGET_CHECKOUT_MS, 0L)
        val isCheckedOut = prefs.getBoolean(KEY_IS_CHECKED_OUT, false)
        val now = System.currentTimeMillis()
        val checkoutStr = formatTime(targetCheckoutMs)

        if (isCheckedOut) {
            val checkOutMs = prefs.getLong(KEY_CHECKOUT_MS, 0L)
            val checkOutStr = if (checkOutMs > 0L) formatTime(checkOutMs) else "earlier"
            return "You are already free to leave, Boss! You completed your shift and checked out on Darwinbox at $checkOutStr."
        }

        val diffMs = targetCheckoutMs - now
        return if (diffMs > 0) {
            val remainingHours = (diffMs / (1000 * 60 * 60)).toInt()
            val remainingMins = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()
            val timePart = when {
                remainingHours > 0 && remainingMins > 0 -> "$remainingHours hours and $remainingMins minutes"
                remainingHours > 0 -> "$remainingHours hours"
                else -> "$remainingMins minutes"
            }
            "You can leave at $checkoutStr, Boss! You have $timePart remaining in your 9-hour shift."
        } else {
            val overdueMins = ((-diffMs) / (1000 * 60)).toInt()
            "You can leave immediately, Boss! Your 9-hour shift finished $overdueMins minutes ago at $checkoutStr. Would you like me to clock you out on Darwinbox?"
        }
    }

    /**
     * Answers: "How much time left?" / "How much shift time is left?".
     */
    fun getRemainingTimeOnly(context: Context): String {
        val prefs = getPrefs(context)
        val shiftDate = prefs.getString(KEY_SHIFT_DATE, null)
        val today = getTodayDateString()

        if (shiftDate != today || !isCheckedInToday(context)) {
            return "No Darwinbox check-in has been logged for today, Boss. Would you like me to clock you in now?"
        }

        val targetCheckoutMs = prefs.getLong(KEY_TARGET_CHECKOUT_MS, 0L)
        val isCheckedOut = prefs.getBoolean(KEY_IS_CHECKED_OUT, false)
        val now = System.currentTimeMillis()
        val checkoutStr = formatTime(targetCheckoutMs)

        if (isCheckedOut) {
            return "Your shift is already complete, Boss! Zero time left."
        }

        val diffMs = targetCheckoutMs - now
        return if (diffMs > 0) {
            val remainingHours = (diffMs / (1000 * 60 * 60)).toInt()
            val remainingMins = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()
            val timePart = when {
                remainingHours > 0 && remainingMins > 0 -> "$remainingHours hours and $remainingMins minutes"
                remainingHours > 0 -> "$remainingHours hours"
                else -> "$remainingMins minutes"
            }
            "You have $timePart left in your 9-hour shift, Boss. Your checkout is at $checkoutStr."
        } else {
            val overdueMins = ((-diffMs) / (1000 * 60)).toInt()
            "Zero time left, Boss! Your shift was completed $overdueMins minutes ago at $checkoutStr. Time to check out!"
        }
    }

    /**
     * Parses explicit spoken time (e.g. "9:15 am", "9.15", "9 15 am", "915 am", "10 am") and sets check-in.
     */
    fun recordManualCheckInTime(context: Context, timeSpoken: String): ShiftInfo? {
        val clean = timeSpoken.trim().lowercase()

        // Match patterns like "9:15 am", "9.15 pm", "9 15 am", "915 am", "9 am", "9:15"
        val regexWithSeparator = Regex("(\\d{1,2})[.:\\s](\\d{2})\\s*(am|pm)?")
        val regexCompact = Regex("(\\d{3,4})\\s*(am|pm)?")
        val regexHourOnly = Regex("(\\d{1,2})\\s*(am|pm)")

        var hour = -1
        var min = 0
        var amPmStr = ""

        val matchSep = regexWithSeparator.find(clean)
        if (matchSep != null) {
            hour = matchSep.groupValues[1].toIntOrNull() ?: -1
            min = matchSep.groupValues[2].toIntOrNull() ?: 0
            amPmStr = matchSep.groupValues[3]
        } else {
            val matchCompact = regexCompact.find(clean)
            if (matchCompact != null) {
                val digits = matchCompact.groupValues[1]
                if (digits.length == 3) {
                    hour = digits.substring(0, 1).toIntOrNull() ?: -1
                    min = digits.substring(1).toIntOrNull() ?: 0
                } else if (digits.length == 4) {
                    hour = digits.substring(0, 2).toIntOrNull() ?: -1
                    min = digits.substring(2).toIntOrNull() ?: 0
                }
                amPmStr = matchCompact.groupValues[2]
            } else {
                val matchHour = regexHourOnly.find(clean)
                if (matchHour != null) {
                    hour = matchHour.groupValues[1].toIntOrNull() ?: -1
                    min = 0
                    amPmStr = matchHour.groupValues[2]
                }
            }
        }

        if (hour < 0 || hour > 23 || min < 0 || min > 59) return null

        // Convert to 24h format based on AM/PM
        if (amPmStr.contains("pm") && hour < 12) {
            hour += 12
        } else if (amPmStr.contains("am") && hour == 12) {
            hour = 0
        } else if (amPmStr.isEmpty()) {
            // Default heuristics for office shifts if AM/PM is omitted:
            // 7..11 are AM (morning checkin)
            // 1..6 are PM (afternoon checkin)
            if (hour in 1..6) hour += 12
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return recordCheckIn(context, cal.timeInMillis)
    }

    private fun scheduleCheckoutAlarm(context: Context, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DarwinboxAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_OVERDUE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Log.i(TAG, "Exact alarm scheduled for: ${formatTime(triggerAtMs)}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set exact alarm, falling back to standard alarm", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    /**
     * Schedules the daily 10:30 AM check alarm.
     * Uses AlarmManager.RTC_WAKEUP so it consumes 0% battery while phone sleeps.
     */
    fun scheduleDaily1030AmCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DarwinboxAlarmReceiver::class.java).apply {
            action = ACTION_DAILY_1030_AM_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DAILY_CHECK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If 10:30 AM has already passed today, schedule for tomorrow 10:30 AM
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            }
            Log.i(TAG, "⏰ Daily 10:30 AM Darwinbox check scheduled for: ${SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault()).format(cal.time)}")
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to standard alarm for 10:30 AM check", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        }
    }

    /**
     * Reschedules shift alarms if device rebooted or app was restarted.
     */
    fun rescheduleAlarmsIfActive(context: Context) {
        if (isCheckedInToday(context) && !isCheckedOutToday(context)) {
            val prefs = getPrefs(context)
            val alertMs = prefs.getLong(KEY_ALERT_MS, 0L)
            if (alertMs > System.currentTimeMillis()) {
                scheduleCheckoutAlarm(context, alertMs)
            }
        }
        scheduleDaily1030AmCheck(context)
    }

    fun cancelCheckoutAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DarwinboxAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_OVERDUE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Checkout alarm canceled.")
    }
}
