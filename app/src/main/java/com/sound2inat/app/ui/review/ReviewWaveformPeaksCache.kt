package com.sound2inat.app.ui.review

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk cache for waveform peak envelopes derived from immutable audio files.
 */
class ReviewWaveformPeaksCache {
    suspend fun getOrCreate(
        audioFile: File,
        draftId: String,
        filesDir: File,
        build: suspend () -> FloatArray,
    ): FloatArray {
        val cacheFile = cacheFile(audioFile, draftId, filesDir)
        val expectedMetadata = expectedMetadata(audioFile)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        readPeaksFromCache(cacheFile, expectedMetadata)?.let {
            Log.d(
                "ReviewVisuals",
                "peaks-cache-hit draft=$draftId file=${audioFile.name} elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms count=${it.size}",
            )
            return it
        }

        Log.d("ReviewVisuals", "peaks-cache-miss draft=$draftId file=${audioFile.name}")
        val buildStarted = android.os.SystemClock.elapsedRealtime()
        val peaks = build()
        Log.i(
            "ReviewVisuals",
            "peaks-build draft=$draftId elapsed=${android.os.SystemClock.elapsedRealtime() - buildStarted}ms count=${peaks.size}",
        )
        writeCache(cacheFile, expectedMetadata, peaks)
        return peaks
    }

    internal fun cacheKey(audioFile: File): String = sha256Hex(expectedMetadata(audioFile))

    internal fun cacheFile(audioFile: File, draftId: String, filesDir: File): File =
        File(File(File(filesDir, CACHE_ROOT), draftId), "${cacheKey(audioFile)}.wpk")

    private fun expectedMetadata(audioFile: File): String = buildString {
        appendLine("audio_path=${audioFile.absolutePath}")
        appendLine("audio_size=${audioFile.length()}")
        appendLine("audio_last_modified=${audioFile.lastModified()}")
        appendLine("target_width=${WaveformBitmap.DEFAULT_TARGET_WIDTH}")
    }

    private fun readPeaksFromCache(cacheFile: File, expectedMetadata: String): FloatArray? {
        if (!cacheFile.exists()) return null
        return try {
            DataInputStream(FileInputStream(cacheFile)).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC) return deleteAndNull(cacheFile)
                val version = input.readInt()
                if (version != FILE_FORMAT_VERSION) return deleteAndNull(cacheFile)
                val metadata = input.readUTF()
                if (metadata != expectedMetadata) return deleteAndNull(cacheFile)
                val count = input.readInt()
                validateCount(count)
                val expectedBytes = count.toLong() * java.lang.Float.BYTES.toLong()
                val remainingBytes = input.available().toLong()
                require(remainingBytes == expectedBytes) {
                    "Unexpected peak payload size: $remainingBytes bytes (expected $expectedBytes)"
                }
                FloatArray(count) { input.readFloat() }
            }
        } catch (_: EOFException) {
            deleteAndNull(cacheFile)
        } catch (_: Throwable) {
            deleteAndNull(cacheFile)
        }
    }

    private fun writeCache(cacheFile: File, metadata: String, peaks: FloatArray) {
        cacheFile.parentFile?.mkdirs()
        val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            DataOutputStream(FileOutputStream(tmpFile)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FILE_FORMAT_VERSION)
                output.writeUTF(metadata)
                output.writeInt(peaks.size)
                for (value in peaks) output.writeFloat(value)
                output.flush()
            }
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
            }
        } catch (_: Throwable) {
            tmpFile.delete()
        }
    }

    private fun validateCount(count: Int) {
        require(count in 0..WaveformBitmap.DEFAULT_TARGET_WIDTH * 2) {
            "Unexpected peak count: $count"
        }
    }

    private fun deleteAndNull(file: File): FloatArray? {
        file.delete()
        return null
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
        }
    }

    companion object {
        private const val CACHE_ROOT = "waveform_peaks_cache"
        private const val MAGIC = 0x57504B31 // "WPK1"
        private const val FILE_FORMAT_VERSION = 1
    }
}
