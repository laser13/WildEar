package com.sound2inat.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Copies [file] into MediaStore Downloads/WildEar/ and returns the resulting [Uri].
     *
     * Requires API 29+. Throws [UnsupportedOperationException] on API 28 — the caller
     * (ReviewViewModel) catches this and shows a Snackbar instead.
     *
     * Does NOT switch dispatchers. Caller must invoke from an IO coroutine.
     */
    suspend fun saveToDownloads(file: File, displayName: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("Saving to Downloads requires Android 10 or later")
        }
        return saveToDownloadsQ(file, displayName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsQ(file: File, displayName: String): Uri {
        require(file.exists() && file.isFile && file.length() > 0L) {
            "Source file missing or empty: ${file.absolutePath}"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WildEar")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore.insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open output stream for $uri")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val updateValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, updateValues, null, null)
        return uri
    }
}
