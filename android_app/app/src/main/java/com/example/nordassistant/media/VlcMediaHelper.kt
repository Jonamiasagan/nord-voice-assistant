package com.example.nordassistant.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.util.ArrayList

/**
 * Encapsulates VLC intent generation, package checks, and playback flags.
 */
object VlcMediaHelper {
    const val VLC_PACKAGE = "org.videolan.vlc"

    fun isVlcInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(VLC_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun createVlcPlaylistIntent(playlistName: String, shuffle: Boolean): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, playlistName)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
            putExtra("android.intent.extra.focus", "vnd.android.cursor.item/playlist")
            @Suppress("DEPRECATION")
            putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, playlistName)
            putExtra("android.intent.extra.playlist", playlistName)
            putExtra("extra_playlist", playlistName)
            if (shuffle) {
                putExtra("android.intent.extra.SHUFFLE", true)
                putExtra("shuffle", true)
                putExtra("EXTRA_SHUFFLE", true)
            }
            setPackage(VLC_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun createVlcSearchIntent(query: String, mediaFocus: String?, shuffle: Boolean): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            if (mediaFocus != null) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocus)
                putExtra("android.intent.extra.focus", mediaFocus)
            }
            if (shuffle) {
                putExtra("android.intent.extra.SHUFFLE", true)
                putExtra("shuffle", true)
                putExtra("EXTRA_SHUFFLE", true)
            }
            setPackage(VLC_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun createVlcTracksIntent(trackUris: List<Uri>): Intent {
        val firstUri = trackUris.first()
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(firstUri, "audio/*")
            setPackage(VLC_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (trackUris.size > 1) {
                val arrayList = ArrayList<Uri>(trackUris)
                putParcelableArrayListExtra("playlist", arrayList)
                putParcelableArrayListExtra("tracks", arrayList)
            }
        }
    }
}

