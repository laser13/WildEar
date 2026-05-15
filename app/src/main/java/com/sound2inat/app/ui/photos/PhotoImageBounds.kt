package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream

internal data class PhotoImageBounds(
    val width: Int,
    val height: Int,
)

internal fun readPhotoImageBounds(path: String): PhotoImageBounds? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    FileInputStream(path).use { input ->
        if (BitmapFactory.decodeStream(input, null, options) == null && options.outWidth <= 0) {
            return null
        }
    }
    val rawWidth = options.outWidth.takeIf { it > 0 } ?: return null
    val rawHeight = options.outHeight.takeIf { it > 0 } ?: return null
    val orientation = runCatching { ExifInterface(path).rotationDegrees() }.getOrDefault(0)
    return if (orientation % 180 == 0) {
        PhotoImageBounds(rawWidth, rawHeight)
    } else {
        PhotoImageBounds(rawHeight, rawWidth)
    }
}

internal fun decodeOrientedBitmap(path: String): Bitmap {
    val source = FileInputStream(path).use { input -> BitmapFactory.decodeStream(input) }
        ?: error("Could not decode bitmap for $path")
    val exif = runCatching { ExifInterface(path) }.getOrNull()
    val rotationDegrees = exif?.rotationDegrees() ?: 0
    if (rotationDegrees == 0) return source

    val matrix = Matrix().apply {
        if (rotationDegrees != 0) {
            postRotate(rotationDegrees.toFloat(), source.width / 2f, source.height / 2f)
        }
    }
    val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (oriented !== source) {
        source.recycle()
    }
    return oriented
}

private fun ExifInterface.rotationDegrees(): Int = when (
    getAttributeInt(EXIF_ORIENTATION_TAG, EXIF_ORIENTATION_NORMAL)
) {
    EXIF_ORIENTATION_ROTATE_90 -> 90
    EXIF_ORIENTATION_ROTATE_180 -> 180
    EXIF_ORIENTATION_ROTATE_270 -> 270
    EXIF_ORIENTATION_TRANSPOSE -> 90
    EXIF_ORIENTATION_TRANSVERSE -> 270
    else -> 0
}

private const val EXIF_ORIENTATION_TAG = "Orientation"
private const val EXIF_ORIENTATION_NORMAL = 1
private const val EXIF_ORIENTATION_ROTATE_180 = 3
private const val EXIF_ORIENTATION_TRANSPOSE = 5
private const val EXIF_ORIENTATION_ROTATE_90 = 6
private const val EXIF_ORIENTATION_TRANSVERSE = 7
private const val EXIF_ORIENTATION_ROTATE_270 = 8
