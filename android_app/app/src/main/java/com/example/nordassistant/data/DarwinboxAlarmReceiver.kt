package com.example.nordassistant.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nordassistant.MainActivity

/**
 * BroadcastReceiver triggered by AlarmManager:
 * 1. Exactly at 9 hours after check-in (shift completed) -> Announces verbally out loud & posts notification.
 * 2. 9 hours + 10 minutes after check-in (overdue grace period) -> Announces verbally out loud & posts urgent notification.
 * 3. Daily at 10:30 AM on working days -> Arms default shift if not already clocked in.
 *
 * Spoken voice output is guaranteed via DevilVoiceSpeaker even if the phone screen is OFF or locked.
 */
class DarwinboxAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DarwinboxAlarmReceiver"
        const val CHANNEL_ID = "devil_darwinbox_shift_alerts"
        const val NOTIFICATION_ID = 9003
        const val ACTION_ALARM_OVERDUE = "com.example.nordassistant.ACTION_DARWINBOX_OVERDUE"
        const val ACTION_ALARM_COMPLETE = "com.example.nordassistant.ACTION_DARWINBOX_COMPLETE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "DarwinboxAlarmReceiver triggered with action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Device rebooted. Rescheduling Darwinbox shift and 10:30 AM check alarms.")
                DarwinboxShiftTracker.rescheduleAlarmsIfActive(context)
                return
            }
            DarwinboxShiftTracker.ACTION_DAILY_1030_AM_CHECK -> {
                handleDaily1030AmCheck(context)
                return
            }
            DarwinboxShiftTracker.ACTION_ALARM_SHIFT_COMPLETE,
            ACTION_ALARM_COMPLETE -> {
                handleShiftCompleteAlarm(context)
                return
            }
            DarwinboxShiftTracker.ACTION_ALARM_OVERDUE,
            ACTION_ALARM_OVERDUE -> {
                handleOvertimeAlarm(context)
                return
            }
            else -> {
                Log.i(TAG, "Unspecified alarm action fallback: $action. Handling as shift complete.")
                handleShiftCompleteAlarm(context)
                return
            }
        }
    }

    private fun handleShiftCompleteAlarm(context: Context) {
        Log.i(TAG, "⏰ 9-Hour Darwinbox shift complete alarm triggered!")

        // If user already checked out, suppress alert
        if (DarwinboxShiftTracker.isCheckedOutToday(context)) {
            Log.i(TAG, "User has already checked out today. Shift complete alert suppressed.")
            return
        }

        // Verify that a check-in was indeed recorded for today
        if (!DarwinboxShiftTracker.isCheckedInToday(context)) {
            Log.w(TAG, "No check-in recorded today. Alarm suppressed.")
            return
        }

        // 1. Speak out loud!
        val speech = "Boss, your 9-hour shift is completed! It is time to check out on Darwinbox."
        Log.i(TAG, "🗣️ DEVIL Speaking out loud: $speech")
        DevilVoiceSpeaker.speak(context, speech)

        // 2. Post high-priority notification with Clock Out action button
        postShiftCompleteNotification(context)
    }

    private fun handleOvertimeAlarm(context: Context) {
        Log.i(TAG, "🚨 Darwinbox overtime checkout alarm triggered!")

        // If user already checked out, suppress alert
        if (DarwinboxShiftTracker.isCheckedOutToday(context)) {
            Log.i(TAG, "User has already checked out today. Overdue alert suppressed.")
            return
        }

        // Verify that a check-in was indeed recorded for today
        if (!DarwinboxShiftTracker.isCheckedInToday(context)) {
            Log.w(TAG, "No check-in recorded today. Alarm suppressed.")
            return
        }

        // 1. Speak out loud!
        val speech = "Boss, your 9-hour shift completed 10 minutes ago and you haven't checked out yet! Please clock out on Darwinbox."
        Log.i(TAG, "🗣️ DEVIL Speaking out loud: $speech")
        DevilVoiceSpeaker.speak(context, speech)

        // 2. Post high-priority overtime notification with Clock Out action button
        postOvertimeNotification(context)
    }

    private fun handleDaily1030AmCheck(context: Context) {
        // 1. Reschedule for tomorrow morning 10:30 AM
        DarwinboxShiftTracker.scheduleDaily1030AmCheck(context)

        // 2. Check if today is an official corporate holiday or weekend off (Sunday / 2nd or 4th Saturday)
        val offStatus = CorporateCalendarEngine.isTodayOffDay()
        if (offStatus.isOffDay) {
            Log.i(TAG, "10:30 AM Check: Today is a corporate holiday/off day (${offStatus.reason}). Stand down - no check-in or checkout required today.")
            return
        }

        // 3. Check if user already clocked in earlier today (e.g. 9:15 AM)
        if (DarwinboxShiftTracker.isCheckedInToday(context)) {
            Log.i(TAG, "10:30 AM Check: Check-in was already recorded earlier today. Shift active.")
            return
        }

        // 4. User requested: If not checked in before 10:30 AM on a working day, assume manual check-in at 10:30 AM
        // Record check-in at 10:30 AM, calculate 9-hour checkout (7:30 PM), and set overtime alert (7:40 PM)
        Log.i(TAG, "10:30 AM Check: No check-in recorded yet on a working day. Assuming manual check-in at 10:30 AM.")
        val info = DarwinboxShiftTracker.recordCheckIn(context, System.currentTimeMillis())

        post1030AmShiftArmedNotification(context, info)
    }

    private fun post1030AmShiftArmedNotification(context: Context, info: DarwinboxShiftTracker.ShiftInfo) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        createNotificationChannel(notificationManager)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("command", "shift status")
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            9011,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = "Manual check-in assumed at ${info.checkInTimeStr}. Your 9-hour shift checkout is scheduled for ${info.checkoutTimeStr}. Overtime alert armed for ${info.alertTimeStr}."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("DEVIL // ⏰ 10:30 AM SHIFT TRACKER")
            .setContentText("Check-in at ${info.checkInTimeStr} logged. Checkout set for ${info.checkoutTimeStr}.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
            Log.i(TAG, "10:30 AM Shift armed notification posted.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post 10:30 AM notification", e)
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Darwinbox Shift & Attendance Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when 9-hour shift completes and Darwinbox checkout is overdue"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setShowBadge(true)
                val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(alertSound, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun postShiftCompleteNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        createNotificationChannel(notificationManager)

        // Action: Clock Out Now (Launches MainActivity with checkout command)
        val clockOutIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("command", "check out")
            putExtra("EXTRA_ACTION", "DARWINBOX_CHECK_OUT")
        }
        val pendingClockOut = PendingIntent.getActivity(
            context,
            9004,
            clockOutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap Notification: Open MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("command", "shift status")
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            9005,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertMessage = "Boss, your 9-hour shift is completed! It's time to check out on Darwinbox."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DEVIL // ⏰ 9-HOUR SHIFT COMPLETE")
            .setContentText("Shift complete! It's time to check out on Darwinbox.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(alertMessage))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingOpen, false)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_send, "Clock Out Now", pendingClockOut)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "📢 Shift complete notification posted successfully (ID: $NOTIFICATION_ID)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post shift complete notification", e)
        }
    }

    private fun postOvertimeNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        createNotificationChannel(notificationManager)

        // Action: Clock Out Now (Launches MainActivity with checkout command)
        val clockOutIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("command", "check out")
            putExtra("EXTRA_ACTION", "DARWINBOX_CHECK_OUT")
        }
        val pendingClockOut = PendingIntent.getActivity(
            context,
            9004,
            clockOutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap Notification: Open MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("command", "shift status")
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            9005,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertMessage = "Boss, your 9-hour shift completed 10 minutes ago! You have not checked out on Darwinbox yet. Tap below to clock out immediately."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DEVIL // 🚨 DARWINBOX CHECKOUT OVERDUE")
            .setContentText("Shift ended 10 mins ago! Darwinbox checkout pending.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(alertMessage))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingOpen, false)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_send, "Clock Out Now", pendingClockOut)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "📢 Overtime notification posted successfully (ID: $NOTIFICATION_ID)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post overtime notification", e)
        }
    }
}
