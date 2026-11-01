package com.example.nordassistant.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.nordassistant.R
import java.util.Calendar

data class FestivalTheme(
    val festivalName: String,
    @DrawableRes val drawableResId: Int,
    val primaryRingColor: Color,
    val secondaryRingColor: Color,
    val greeting: String
)

object FestivalThemeManager {

    /**
     * Resolves the active festival theme dynamically based on current date.
     * Defaults to the classic Gideon face if no active festival matches today.
     */
    fun getActiveFestivalTheme(): FestivalTheme {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) // 0-based: 0 = Jan, 10 = Nov
        val day = cal.get(Calendar.DAY_OF_MONTH)

        return when {
            // Kannada Rajyotsava: November 1
            month == Calendar.NOVEMBER && day == 1 -> {
                FestivalTheme(
                    festivalName = "Kannada Rajyotsava",
                    drawableResId = R.drawable.gideon_kannada_rajyotsava,
                    primaryRingColor = Color(0xFFFFD700), // Haldi Yellow
                    secondaryRingColor = Color(0xFFD32F2F), // Kumkuma Red
                    greeting = "ಶುಭ ಕನ್ನಡ ರಾಜ್ಯೋತ್ಸವ! Happy Kannada Rajyotsava Boss!"
                )
            }
            // Dhanteras: November 6 (2026) / late autumn
            month == Calendar.NOVEMBER && day == 6 -> {
                FestivalTheme(
                    festivalName = "Dhanteras",
                    drawableResId = R.drawable.gideon_dhanteras,
                    primaryRingColor = Color(0xFFFFD700), // Brass Gold
                    secondaryRingColor = Color(0xFFE67E22), // Copper Ochre
                    greeting = "Happy Dhanteras Boss! Wishing you boundless prosperity."
                )
            }
            // Naraka Chaturdashi (Choti Diwali): November 7 (2026)
            month == Calendar.NOVEMBER && day == 7 -> {
                FestivalTheme(
                    festivalName = "Naraka Chaturdashi",
                    drawableResId = R.drawable.gideon_naraka_chaturdashi,
                    primaryRingColor = Color(0xFFFF9933), // Electric Saffron
                    secondaryRingColor = Color(0xFF8E44AD), // Twilight Purple
                    greeting = "Happy Naraka Chaturdashi Boss! May all darkness be dispelled."
                )
            }
            // Deepavali / Main Diwali: November 8 (2026)
            month == Calendar.NOVEMBER && day == 8 -> {
                FestivalTheme(
                    festivalName = "Deepavali",
                    drawableResId = R.drawable.gideon_diwali,
                    primaryRingColor = Color(0xFFF39C12), // Deep Diya Gold
                    secondaryRingColor = Color(0xFFE74C3C), // Festive Vermilion
                    greeting = "Happy Deepavali Boss! May your life be illuminated with light."
                )
            }
            // Govardhan Puja: November 10 (2026)
            month == Calendar.NOVEMBER && day == 10 -> {
                FestivalTheme(
                    festivalName = "Govardhan Puja",
                    drawableResId = R.drawable.gideon_diwali,
                    primaryRingColor = Color(0xFF27AE60), // Emerald Green
                    secondaryRingColor = Color(0xFFF1C40F), // Turmeric
                    greeting = "Happy Govardhan Puja and Annakut Boss!"
                )
            }
            // Bhai Dooj: November 11 (2026)
            month == Calendar.NOVEMBER && day == 11 -> {
                FestivalTheme(
                    festivalName = "Bhai Dooj",
                    drawableResId = R.drawable.gideon_bhai_dooj,
                    primaryRingColor = Color(0xFFE74C3C), // Roli Crimson
                    secondaryRingColor = Color(0xFFFF7675), // Rose Pink
                    greeting = "Happy Bhai Dooj Boss!"
                )
            }
            // Default Classic S.T.A.R. Labs Sci-Fi Hologram
            else -> {
                FestivalTheme(
                    festivalName = "Gideon Standard",
                    drawableResId = R.drawable.gideon_face_sq,
                    primaryRingColor = Color(0xFF00E5FF),
                    secondaryRingColor = Color(0xFF0091EA),
                    greeting = "G.I.D.E.O.N. Online. Awaiting your command, Boss."
                )
            }
        }
    }
}
