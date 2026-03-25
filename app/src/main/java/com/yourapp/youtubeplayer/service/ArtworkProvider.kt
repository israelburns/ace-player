package com.yourapp.youtubeplayer.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * ArtworkProvider — serves YouTube thumbnails to Android Auto via content:// URIs.
 * Android Auto cannot fetch https:// artwork directly, so this ContentProvider
 * downloads, caches, and serves thumbnails locally.
 *
 * URI format: content://com.yourapp.youtubeplayer.artwork/{videoId}
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.yourapp.youtubeplayer.artwork"

        fun getArtworkUri(videoId: String): Uri {
            return Uri.parse("content://$AUTHORITY/$videoId")
        }
    }

    private val cacheDir: File by lazy {
        File(context!!.cacheDir, "artwork").also { it.mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val videoId = uri.lastPathSegment ?: return null
        val cacheFile = File(cacheDir, "$videoId.jpg")

        // Serve from cache if exists and is recent (< 24 hours)
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 86400000) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Download thumbnail
        return try {
            val url = URL("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val bytes = connection.getInputStream().readBytes()

            FileOutputStream(cacheFile).use { it.write(bytes) }
            ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            // If download fails and we have an old cache, use it
            if (cacheFile.exists()) {
                ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } else null
        }
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<String>?): Int = 0
}
