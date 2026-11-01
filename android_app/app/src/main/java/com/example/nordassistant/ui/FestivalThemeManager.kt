package com.example.nordassistant.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.nordassistant.R
import java.util.Calendar

data class FestivalTheme(
    val festivalName: String,
    @param:DrawableRes val drawableResId: Int,
    val primaryRingColor: Color,
    val secondaryRingColor: Color,
    val accentColor: Color,
    val bgTopColor: Color,
    val bgBottomColor: Color,
    val dotMatrixColor: Color,
    val greeting: String
)

object FestivalThemeManager {

    /**
     * Resolves the active festival theme dynamically based on current date.
     * Includes custom background gradients, dot-matrix tints, and accent colors.
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
                    accentColor = Color(0xFFFFEB3B),
                    bgTopColor = Color(0xFF2E1C00), // Rich dark turmeric ambient glow
                    bgBottomColor = Color(0xFF260505), // Rich dark crimson ambient glow
                    dotMatrixColor = Color(0xFFFFD700),
                    greeting = "ಶುಭ ಕನ್ನಡ ರಾಜ್ಯೋತ್ಸವ! Happy Kannada Rajyotsava Boss!"
                )
            }
            // Dhanteras: November 6 (2026)
            month == Calendar.NOVEMBER && day == 6 -> {
                FestivalTheme(
                    festivalName = "Dhanteras",
                    drawableResId = R.drawable.gideon_dhanteras,
                    primaryRingColor = Color(0xFFFFD700), // Brass Gold
                    secondaryRingColor = Color(0xFFE67E22), // Copper Ochre
                    accentColor = Color(0xFFF1C40F),
                    bgTopColor = Color(0xFF241B03),
                    bgBottomColor = Color(0xFF1B0F03),
                    dotMatrixColor = Color(0xFFFFD700),
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
                    accentColor = Color(0xFFFFA726),
                    bgTopColor = Color(0xFF1D0B2B), // Deep twilight ambient
                    bgBottomColor = Color(0xFF261002), // Warm saffron ambient
                    dotMatrixColor = Color(0xFFFF9933),
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
                    accentColor = Color(0xFFFFC107),
                    bgTopColor = Color(0xFF2A1502), // Warm glowing diya darkness
                    bgBottomColor = Color(0xFF200707),
                    dotMatrixColor = Color(0xFFF39C12),
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
                    accentColor = Color(0xFF2ECC71),
                    bgTopColor = Color(0xFF041F10),
                    bgBottomColor = Color(0xFF1F1C02),
                    dotMatrixColor = Color(0xFF2ECC71),
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
                    accentColor = Color(0xFFFF5252),
                    bgTopColor = Color(0xFF240608),
                    bgBottomColor = Color(0xFF1A0A17),
                    dotMatrixColor = Color(0xFFFF7675),
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
                    accentColor = Color(0xFF18FFFF),
                    bgTopColor = Color(0xFF030712),
                    bgBottomColor = Color(0xFF020B1A),
                    dotMatrixColor = Color(0xFF00E5FF),
                    greeting = "G.I.D.E.O.N. Online. Awaiting your command, Boss."
                )
            }
        }
    }
}
