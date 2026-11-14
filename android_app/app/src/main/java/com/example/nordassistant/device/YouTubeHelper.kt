package com.example.nordassistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.nordassistant.DevilAccessibilityService

/**
 * Native YouTube automation:
 * 1. Search content directly in YouTube app
 * 2. Refresh YouTube feed via gesture injection / Home tab
 */
object YouTubeHelper {
    private const val TAG = "YouTubeHelper"
    private const val YOUTUBE_PKG = "com.google.android.youtube"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Searches content directly inside the YouTube app via Intent.ACTION_VIEW.
     */
    fun searchYouTube(context: Context, query: String): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return openYouTubeApp(context)
        }

        return try {
            val searchUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery))
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage(YOUTUBE_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Searching $cleanQuery on YouTube, Boss."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube search intent, falling back to browser", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                "Searching $cleanQuery on YouTube, Boss."
            } catch (e2: Exception) {
                "I couldn't open YouTube to search, Boss."
            }
        }
    }

    /**
     * Opens YouTube app.
     */
    fun openYouTubeApp(context: Context): String {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(YOUTUBE_PKG)
                ?: pm.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opening YouTube, Boss."
            } else {
                "YouTube is not installed on your phone, Boss."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open YouTube", e)
            "Could not open YouTube, Boss."
        }
    }

    /**
     * Refreshes the YouTube feed using accessibility service pull-to-refresh swipe.
     * If YouTube is not in foreground, brings it forward first, then dispatches swipe.
     */
    fun refreshYouTube(context: Context): String {
        try {
            // Bring YouTube to foreground
            openYouTubeApp(context)

            // Trigger swipe gesture after a brief window settling delay
            mainHandler.postDelayed({
                DevilAccessibilityService.triggerYouTubeRefresh()
            }, 600)

            return "Refreshing YouTube feed, Boss."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh YouTube", e)
            return "Could not refresh YouTube, Boss."
        }
    }
}
