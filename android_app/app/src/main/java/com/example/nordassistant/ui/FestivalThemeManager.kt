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
    val effectType: ParticleEffectType = ParticleEffectType.NONE,
    val greeting: String,
    val purposeBrief: String = ""
)

object FestivalThemeManager {

    /**
     * Preview flag:
     * null = Strictly automatic resolution according to the active calendar festival day!
     * String value = Force-preview any specific festival for testing.
     */
    var previewFestival: String? = null

    fun getRajyotsavaTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Kannada Rajyotsava",
            drawableResId = R.drawable.gideon_kannada_rajyotsava,
            primaryRingColor = Color(0xFFFFD700), // Haldi Yellow
            secondaryRingColor = Color(0xFFD32F2F), // Kumkuma Red
            accentColor = Color(0xFFFFEB3B),
            bgTopColor = Color(0xFF2E1C00), // Rich dark turmeric ambient glow
            bgBottomColor = Color(0xFF260505), // Rich dark crimson ambient glow
            dotMatrixColor = Color(0xFFFFD700),
            effectType = ParticleEffectType.RAJYOTSAVA_CONFETTI,
            greeting = "ಶುಭ ಕನ್ನಡ ರಾಜ್ಯೋತ್ಸವ! Happy Kannada Rajyotsava Boss!",
            purposeBrief = "PURPOSE: Celebrates Karnataka Rajyotsava (Nov 1, 1956) uniting all Kannada-speaking regions under the iconic yellow and red flag, honoring Karnataka's rich cultural sovereignty and heritage."
        )
    }

    fun getDhanterasTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Dhanteras",
            drawableResId = R.drawable.gideon_dhanteras,
            primaryRingColor = Color(0xFFFFD700), // Brass Gold
            secondaryRingColor = Color(0xFFE67E22), // Copper Ochre
            accentColor = Color(0xFFF1C40F),
            bgTopColor = Color(0xFF281C02), // Deep opulent gold aura
            bgBottomColor = Color(0xFF160E02),
            dotMatrixColor = Color(0xFFFFD700),
            effectType = ParticleEffectType.DHANTERAS_GOLD_COINS,
            greeting = "Happy Dhanteras Boss! Wishing you boundless prosperity and abundance.",
            purposeBrief = "PURPOSE: Welcomes Lord Dhanvantari (father of Ayurveda & cosmic healer) with the Amrit Kalash of health, honoring Goddess Lakshmi & Lord Kuber to safeguard the home from untimely harm."
        )
    }

    fun getNarakaChaturdashiTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Naraka Chaturdashi",
            drawableResId = R.drawable.gideon_naraka_chaturdashi,
            primaryRingColor = Color(0xFFFF9933), // Electric Saffron
            secondaryRingColor = Color(0xFF8E44AD), // Twilight Purple
            accentColor = Color(0xFFFFA726),
            bgTopColor = Color(0xFF1D0B2B), // Deep twilight ambient
            bgBottomColor = Color(0xFF261002), // Warm saffron ambient
            dotMatrixColor = Color(0xFFFF9933),
            effectType = ParticleEffectType.NARAKA_AARTI_FLAMES,
            greeting = "Happy Naraka Chaturdashi Boss! May all darkness be dispelled.",
            purposeBrief = "PURPOSE: Commemorates Lord Krishna & Satyabhama slaying the tyrant Narakasura to liberate 16,000 souls; celebrated with sacred pre-dawn Abhyanga Snan bath and Aarti to vanquish evil."
        )
    }

    fun getDiwaliTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Deepavali",
            drawableResId = R.drawable.gideon_diwali,
            primaryRingColor = Color(0xFFF39C12), // Deep Diya Gold
            secondaryRingColor = Color(0xFFE74C3C), // Festive Vermilion
            accentColor = Color(0xFFFFC107),
            bgTopColor = Color(0xFF2A1502), // Warm glowing diya darkness
            bgBottomColor = Color(0xFF200707),
            dotMatrixColor = Color(0xFFF39C12),
            effectType = ParticleEffectType.DIWALI_FIREWORKS,
            greeting = "Happy Deepavali Boss! May your life be illuminated with joy and prosperity.",
            purposeBrief = "PURPOSE: Celebrates Lord Rama's triumphant return to Ayodhya and Goddess Lakshmi's blessing of inner light, victory of Dharma over adharma, dispelling ignorance through glowing diyas and crackers."
        )
    }

    fun getGovardhanTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Govardhan Puja",
            drawableResId = R.drawable.gideon_diwali,
            primaryRingColor = Color(0xFF27AE60), // Emerald Green
            secondaryRingColor = Color(0xFFF1C40F), // Turmeric
            accentColor = Color(0xFF2ECC71),
            bgTopColor = Color(0xFF041F10),
            bgBottomColor = Color(0xFF1F1C02),
            dotMatrixColor = Color(0xFF2ECC71),
            effectType = ParticleEffectType.NARAKA_AARTI_FLAMES,
            greeting = "Happy Govardhan Puja and Annakut Boss!",
            purposeBrief = "PURPOSE: Honors Lord Krishna lifting Mount Govardhan to protect the people of Braj from torrential wrath, celebrated with Annakut (mountain of sanctified festive food offerings)."
        )
    }

    fun getBhaiDoojTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Bhai Dooj",
            drawableResId = R.drawable.gideon_bhai_dooj,
            primaryRingColor = Color(0xFFE74C3C), // Roli Crimson
            secondaryRingColor = Color(0xFFFF7675), // Rose Pink
            accentColor = Color(0xFFFF5252),
            bgTopColor = Color(0xFF240608),
            bgBottomColor = Color(0xFF1A0A17),
            dotMatrixColor = Color(0xFFFF7675),
            effectType = ParticleEffectType.BHAI_DOOJ_ROSE_PETALS,
            greeting = "Happy Bhai Dooj Boss! Sister Gideon assures: 'I will protect you from all harm.'",
            purposeBrief = "PURPOSE: Sister Gideon assures: 'I will protect you, Brother.' Any brother who receives her sacred tilak is granted divine immunity from untimely death or spiritual harm, honoring Yama & Yamuna's sacred eternal bond."
        )
    }

    fun getChhathPujaTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Chhath Puja",
            drawableResId = R.drawable.gideon_chhath_puja,
            primaryRingColor = Color(0xFFFF9800), // Solar Orange
            secondaryRingColor = Color(0xFFFFD54F), // Sunrise Gold
            accentColor = Color(0xFFFFE082),
            bgTopColor = Color(0xFF2E1500), // Dawn Sun glow
            bgBottomColor = Color(0xFF1A0C00), // River Ghat bank
            dotMatrixColor = Color(0xFFFFB74D),
            effectType = ParticleEffectType.NARAKA_AARTI_FLAMES,
            greeting = "Happy Chhath Puja Boss! May Lord Surya bless you with vitality and peace.",
            purposeBrief = "PURPOSE: Sacred Vedic thanksgiving to Lord Surya (Sun God) and Chhathi Maiya for sustaining life, health, and cosmic energy, performed standing in river waters with Arghya."
        )
    }

    fun getDefaultGideonTheme(): FestivalTheme {
        return FestivalTheme(
            festivalName = "Gideon Standard",
            drawableResId = R.drawable.gideon_face_sq,
            primaryRingColor = Color(0xFF00E5FF),
            secondaryRingColor = Color(0xFF0091EA),
            accentColor = Color(0xFF18FFFF),
            bgTopColor = Color(0xFF030712),
            bgBottomColor = Color(0xFF020B1A),
            dotMatrixColor = Color(0xFF00E5FF),
            greeting = "G.I.D.E.O.N. Online. Awaiting your command, Boss.",
            purposeBrief = ""
        )
    }

    /**
     * Resolves the active festival theme dynamically based on the current calendar date.
     */
    fun getActiveFestivalTheme(): FestivalTheme {
        // If preview mode is requested, use it; otherwise automatically resolve by actual date!
        if (previewFestival != null) {
            return when (previewFestival) {
                "Kannada Rajyotsava" -> getRajyotsavaTheme()
                "Dhanteras" -> getDhanterasTheme()
                "Naraka Chaturdashi" -> getNarakaChaturdashiTheme()
                "Deepavali" -> getDiwaliTheme()
                "Govardhan Puja" -> getGovardhanTheme()
                "Bhai Dooj" -> getBhaiDoojTheme()
                "Chhath Puja" -> getChhathPujaTheme()
                else -> getDefaultGideonTheme()
            }
        }

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) // 0-based: 0 = Jan, 10 = Nov
        val day = cal.get(Calendar.DAY_OF_MONTH)

        return when {
            // Kannada Rajyotsava: November 1
            month == Calendar.NOVEMBER && day == 1 -> getRajyotsavaTheme()

            // Dhanteras: November 6 (2026)
            month == Calendar.NOVEMBER && day == 6 -> getDhanterasTheme()

            // Naraka Chaturdashi (Choti Diwali): November 7 (2026)
            month == Calendar.NOVEMBER && day == 7 -> getNarakaChaturdashiTheme()

            // Deepavali / Main Diwali: November 8 (2026)
            month == Calendar.NOVEMBER && day == 8 -> getDiwaliTheme()

            // Govardhan Puja: November 10 (2026)
            month == Calendar.NOVEMBER && day == 10 -> getGovardhanTheme()

            // Bhai Dooj: November 11 (2026)
            month == Calendar.NOVEMBER && day == 11 -> getBhaiDoojTheme()

            // Chhath Puja: November 13 to 16 (2026)
            month == Calendar.NOVEMBER && day in 13..16 -> getChhathPujaTheme()

            // Default Classic S.T.A.R. Labs Sci-Fi Hologram
            else -> getDefaultGideonTheme()
        }
    }
}
