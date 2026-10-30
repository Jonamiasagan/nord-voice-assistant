package com.example.nordassistant.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Handles intent creation and dispatching for Google Maps routes,
 * distance previews, and turn-by-turn navigation.
 */
object MapsNavigationHelper {
    private const val TAG = "MapsNavigationHelper"
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun openRoute(context: Context, origin: String?, destination: String): Boolean {
        return try {
            val mapsUri = if (origin.isNullOrBlank()) {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")
            } else {
                Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(origin)}&destination=${Uri.encode(destination)}")
            }
            val mapIntent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                setPackage(MAPS_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, mapsUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Google Maps route to $destination", e)
            false
        }
    }

    fun startTurnByTurn(context: Context, destination: String): Boolean {
        return try {
            val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage(MAPS_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (navIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(navIntent)
            } else {
                val fallbackUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting turn-by-turn navigation to $destination", e)
            false
        }
    }
}
