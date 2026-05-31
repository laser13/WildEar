package com.sound2inat.audio

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Android-side helpers that turn a `[height][width]` ARGB matrix from
 * [SpectrogramRenderer] into a PNG file on disk. Lives in its own file so
 * [SpectrogramRenderer] can be unit-tested on the JVM without mocking
 * [Bitmap].
 */
object SpectrogramBitmap {

    /** PNG quality; the encoder ignores this for [Bitmap.CompressFormat.PNG]. */
    private const val PNG_QUALITY = 100

    /**
     * Encodes the [pixels] matrix (ARGB ints, row-major) into a PNG written
     * to [target]. Parent directories are created as needed.
     */
    fun writePng(pixels: Array<IntArray>, target: File) {
        require(pixels.isNotEmpty()) { "pixels must not be empty" }
        val height = pixels.size
        val width = pixels[0].size
        require(width > 0) { "pixels rows must not be empty" }
        val flat = IntArray(width * height)
        for (y in 0 until height) {
            val row = pixels[y]
            require(row.size == width) { "pixels rows must all have the same width" }
            System.arraycopy(row, 0, flat, y * width, width)
        }
        val bitmap = Bitmap.createBitmap(flat, width, height, Bitmap.Config.ARGB_8888)
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, fos)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
