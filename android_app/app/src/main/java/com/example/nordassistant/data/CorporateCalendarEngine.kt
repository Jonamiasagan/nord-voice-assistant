package com.example.nordassistant.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Intelligent Calendar & Holiday Engine for DEVIL.
 * Provides exact answers for:
 * 1. Saturday calculations (1st, 2nd, 3rd, 4th, 5th Saturday, upcoming Saturday rank, total Saturdays in month).
 * 2. Current week telemetry (Week of month, week of year, date range).
 * 3. Darwinbox & Corporate Holidays (Upcoming holidays, month holidays, long weekends, custom company holidays).
 */
object CorporateCalendarEngine {

    private const val TAG = "CorporateCalendar"
    private const val PREFS_NAME = "devil_corporate_calendar_prefs"
    private const val KEY_CUSTOM_HOLIDAYS = "custom_holidays_json"

    data class Holiday(
        val name: String,
        val month: Int, // 1-indexed (1 = Jan, 12 = Dec)
        val day: Int,
        val year: Int = 2026,
        val isOptional: Boolean = false,
        val description: String = ""
    ) {
        fun toCalendar(): Calendar {
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        fun formattedDate(): String {
            val cal = toCalendar()
            return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(cal.time)
        }

        fun shortDate(): String {
            val cal = toCalendar()
            return SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time)
        }
    }

    // Default 2026 Indian & Global Corporate Tech Holidays (Standard Darwinbox Calendar)
    private val standardHolidays = listOf(
        Holiday("New Year's Day", 1, 1, 2026),
        Holiday("Makar Sankranti / Pongal", 1, 14, 2026),
        Holiday("Republic Day", 1, 26, 2026, description = "National Holiday - Long Weekend"),
        Holiday("Maha Shivaratri", 2, 17, 2026),
        Holiday("Holi", 3, 4, 2026),
        Holiday("Ugadi / Gudi Padwa", 3, 20, 2026, description = "Long Weekend"),
        Holiday("Id-ul-Fitr (Ramzan)", 3, 21, 2026),
        Holiday("Good Friday", 4, 3, 2026, description = "Long Weekend"),
        Holiday("Ambedkar Jayanti", 4, 14, 2026),
        Holiday("May Day / Labor Day", 5, 1, 2026, description = "Long Weekend"),
        Holiday("Bakrid / Eid al-Adha", 5, 27, 2026),
        Holiday("Independence Day", 8, 15, 2026, description = "National Holiday"),
        Holiday("Raksha Bandhan", 8, 27, 2026),
        Holiday("Krishna Janmashtami", 9, 4, 2026, description = "Long Weekend"),
        Holiday("Ganesh Chaturthi", 9, 15, 2026),
        Holiday("Gandhi Jayanti", 10, 2, 2026, description = "National Holiday - Long Weekend"),
        Holiday("Maha Navami", 10, 19, 2026),
        Holiday("Dussehra / Vijaya Dashami", 10, 20, 2026),
        Holiday("Diwali / Deepavali", 11, 8, 2026, description = "Festival of Lights"),
        Holiday("Govardhan Puja / Balipratipada", 11, 9, 2026, description = "Long Weekend"),
        Holiday("Guru Nanak Jayanti", 11, 24, 2026),
        Holiday("Christmas", 12, 25, 2026, description = "Long Weekend")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════════════════════════════════════════════════════════
    // ── 1. SATURDAY & WEEKEND INTELLIGENCE ─────────────────────────────
    // ═════════════════════════════════════════════════════════════════════

    data class SaturdayDetails(
        val dayOfMonth: Int,
        val rank: Int, // 1st, 2nd, 3rd, 4th, 5th
        val rankStr: String,
        val dateStr: String,
        val isOffDay: Boolean // Standard corporate policy: 2nd & 4th Saturdays are off
    )

    /**
     * Computes all Saturdays for the current (or specified) month.
     */
    fun getSaturdaysForMonth(cal: Calendar = Calendar.getInstance()): List<SaturdayDetails> {
        val testCal = cal.clone() as Calendar
        testCal.set(Calendar.DAY_OF_MONTH, 1)
        val maxDays = testCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(testCal.time)

        val saturdays = mutableListOf<SaturdayDetails>()
        var count = 0

        for (day in 1..maxDays) {
            testCal.set(Calendar.DAY_OF_MONTH, day)
            if (testCal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
                count++
                val rankStr = when (count) {
                    1 -> "1st"
                    2 -> "2nd"
                    3 -> "3rd"
                    4 -> "4th"
                    5 -> "5th"
                    else -> "${count}th"
                }
                val isOff = (count == 2 || count == 4)
                val dateStr = "$monthName $day"
                saturdays.add(SaturdayDetails(day, count, rankStr, dateStr, isOff))
            }
        }
        return saturdays
    }

    /**
     * Answers: "How many Saturdays do we have this month?" / "List Saturdays".
     */
    fun getMonthSaturdaysSummary(cal: Calendar = Calendar.getInstance()): String {
        val saturdays = getSaturdaysForMonth(cal)
        val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

        val sb = StringBuilder()
        sb.append("We have ${saturdays.size} Saturdays in $monthName, Boss: ")

        val listStr = saturdays.joinToString(", ") { sat ->
            val status = if (sat.isOffDay) "(Holiday / Off)" else "(Working)"
            "${sat.rankStr} Saturday on ${sat.dateStr} $status"
        }
        sb.append(listStr)
        sb.append(". Generally, 2nd and 4th Saturdays are corporate holidays.")
        return sb.toString()
    }

    /**
     * Answers: "Is coming Saturday a second Saturday?" / "Is this Saturday 2nd Saturday?".
     */
    fun checkComingSaturday(targetRank: Int? = null): String {
        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_MONTH)
        val monthSaturdays = getSaturdaysForMonth(now)

        // Find the next upcoming Saturday (today or in future)
        val upcomingSat = monthSaturdays.firstOrNull { it.dayOfMonth >= currentDay }

        if (upcomingSat == null) {
            // Next Saturday is in next month
            val nextMonthCal = (now.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 1) }
            val nextMonthSats = getSaturdaysForMonth(nextMonthCal)
            val firstSat = nextMonthSats.firstOrNull() ?: return "Unable to determine next Saturday."
            return "There are no more Saturdays this month, Boss. The next Saturday will be the 1st Saturday of next month on ${firstSat.dateStr}."
        }

        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(now.time)
        val daysUntil = upcomingSat.dayOfMonth - currentDay
        val timingStr = when (daysUntil) {
            0 -> "today"
            1 -> "tomorrow"
            else -> "this coming Saturday, ${upcomingSat.dateStr}"
        }

        // If user specifically asked about 2nd Saturday (targetRank == 2)
        if (targetRank != null) {
            val isTarget = (upcomingSat.rank == targetRank)
            val targetStr = when (targetRank) {
                1 -> "1st"
                2 -> "2nd"
                3 -> "3rd"
                4 -> "4th"
                5 -> "5th"
                else -> "${targetRank}th"
            }

            return if (isTarget) {
                "Yes Boss! $timingStr is indeed the $targetStr Saturday of $monthName (an off day / holiday)."
            } else {
                val secondSat = monthSaturdays.find { it.rank == targetRank }
                val extra = if (secondSat != null) {
                    if (secondSat.dayOfMonth < currentDay) {
                        "The $targetStr Saturday was already completed on ${secondSat.dateStr}."
                    } else {
                        "The $targetStr Saturday is coming up on ${secondSat.dateStr}."
                    }
                } else ""
                "No Boss. $timingStr is the ${upcomingSat.rankStr} Saturday of $monthName (${if (upcomingSat.isOffDay) "Off day" else "Working"}). $extra"
            }
        }

        // Generic query about the coming Saturday
        val offStatus = if (upcomingSat.isOffDay) "an off day / holiday" else "a regular working day"
        return "This coming Saturday is on ${upcomingSat.dateStr}, Boss. It is the ${upcomingSat.rankStr} Saturday of the month, which is $offStatus."
    }

    // ═════════════════════════════════════════════════════════════════════
    // ── 2. CURRENT WEEK TELEMETRY ─────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Answers: "What is this week?" / "Which week of the month is this?".
     */
    fun getWeekInfo(cal: Calendar = Calendar.getInstance()): String {
        val weekOfMonth = cal.get(Calendar.WEEK_OF_MONTH)
        val weekOfYear = cal.get(Calendar.WEEK_OF_YEAR)
        val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

        val rankStr = when (weekOfMonth) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            4 -> "4th"
            5 -> "5th"
            else -> "${weekOfMonth}th"
        }

        // Calculate Monday to Sunday dates of current week
        val mondayCal = cal.clone() as Calendar
        mondayCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val sundayCal = cal.clone() as Calendar
        sundayCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        if (sundayCal.before(mondayCal)) {
            sundayCal.add(Calendar.WEEK_OF_YEAR, 1)
        }

        val monDate = SimpleDateFormat("MMM d", Locale.getDefault()).format(mondayCal.time)
        val sunDate = SimpleDateFormat("MMM d", Locale.getDefault()).format(sundayCal.time)

        return "We are currently in the $rankStr week of $monthName (Week $weekOfYear of the year), Boss. This week runs from Monday, $monDate to Sunday, $sunDate."
    }

    // ═════════════════════════════════════════════════════════════════════
    // ── 3. DARWINBOX & CORPORATE HOLIDAY INTELLIGENCE ─────────────────
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Answers: "When is the next holiday?" / "What is the next upcoming holiday?".
     */
    fun getNextHoliday(cal: Calendar = Calendar.getInstance()): String {
        val now = cal.clone() as Calendar
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)

        val allHolidays = standardHolidays.sortedBy { it.toCalendar().timeInMillis }
        val next = allHolidays.firstOrNull { it.toCalendar().timeInMillis >= now.timeInMillis }

        if (next == null) {
            return "No more official corporate holidays remaining in the current calendar year, Boss. The next holiday will be New Year's Day on January 1st."
        }

        val nextCal = next.toCalendar()
        val diffMs = nextCal.timeInMillis - now.timeInMillis
        val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()

        val countdownStr = when (diffDays) {
            0 -> "today!"
            1 -> "tomorrow (${next.shortDate()})"
            else -> "in $diffDays days on ${next.formattedDate()}"
        }

        val extra = if (next.description.isNotEmpty()) " (${next.description})" else ""
        return "The next corporate holiday is ${next.name}$extra, $countdownStr, Boss."
    }

    /**
     * Answers: "What holidays do we have this month?" / "List holidays in October/November".
     */
    fun getHolidaysForMonth(month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1): String {
        val monthHolidays = standardHolidays.filter { it.month == month }
        val testCal = Calendar.getInstance().apply { set(Calendar.MONTH, month - 1) }
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(testCal.time)

        if (monthHolidays.isEmpty()) {
            return "There are no official public or corporate holidays listed for $monthName, Boss. Only regular 2nd and 4th Saturday weekend offs apply."
        }

        val listStr = monthHolidays.joinToString("; ") { h ->
            "${h.name} on ${h.shortDate()}${if (h.description.isNotEmpty()) " (${h.description})" else ""}"
        }
        return "Here are the holidays for $monthName, Boss: $listStr."
    }

    /**
     * Checks if a specific day name or date is a holiday (e.g. "Is coming Friday a holiday?").
     */
    fun isUpcomingDayHoliday(dayName: String): String {
        val lower = dayName.lowercase().trim()
        val targetDayOfWeek = when {
            lower.contains("mon") -> Calendar.MONDAY
            lower.contains("tue") -> Calendar.TUESDAY
            lower.contains("wed") -> Calendar.WEDNESDAY
            lower.contains("thu") -> Calendar.THURSDAY
            lower.contains("fri") -> Calendar.FRIDAY
            lower.contains("sat") -> Calendar.SATURDAY
            lower.contains("sun") -> Calendar.SUNDAY
            else -> return "Which day would you like me to check, Boss?"
        }

        val testCal = Calendar.getInstance()
        while (testCal.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek) {
            testCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val testMonth = testCal.get(Calendar.MONTH) + 1
        val testDay = testCal.get(Calendar.DAY_OF_MONTH)
        val formattedDate = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(testCal.time)

        // Check 2nd / 4th Saturday
        if (targetDayOfWeek == Calendar.SATURDAY) {
            val monthSats = getSaturdaysForMonth(testCal)
            val thisSat = monthSats.find { it.dayOfMonth == testDay }
            if (thisSat != null && thisSat.isOffDay) {
                return "Yes Boss! $formattedDate is the ${thisSat.rankStr} Saturday of the month, so it is a holiday / weekend off."
            }
        }

        // Check official holidays
        val holiday = standardHolidays.find { it.month == testMonth && it.day == testDay }
        return if (holiday != null) {
            "Yes Boss! $formattedDate is a holiday for ${holiday.name}."
        } else {
            "No Boss, $formattedDate is a normal working day according to the Darwinbox corporate calendar."
        }
    }

    /**
     * Allows user to store custom company holidays from Darwinbox via voice.
     */
    fun registerCustomHoliday(context: Context, holidayName: String, month: Int, day: Int, year: Int = 2026) {
        val entry = "$holidayName|$year-$month-$day"
        val prefs = getPrefs(context)
        val existing = prefs.getStringSet("custom_holidays", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        existing.add(entry)
        prefs.edit().putStringSet("custom_holidays", existing).apply()

        // Also save to BossMemoryVault
        try {
            val vault = BossMemoryVault.getInstance(context)
            vault.remember("holiday $holidayName", "$holidayName on $year-$month-$day", "routine")
        } catch (e: Exception) {
            Log.w(TAG, "Error registering holiday into vault", e)
        }
    }
}
