package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class PhotoImageCropper @Inject constructor() {
    fun cropCenterSquare(source: File, destination: File): CroppedImageInfo {
        require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
        destination.parentFile?.mkdirs()

        val decoded = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Could not decode ${source.absolutePath}")
        val size = minOf(decoded.width, decoded.height)
        val left = ((decoded.width - size) / 2).coerceAtLeast(0)
        val top = ((decoded.height - size) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(decoded, left, top, size, size)
        decoded.recycle()

        FileOutputStream(destination).use { out ->
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                error("Could not write cropped photo to ${destination.absolutePath}")
            }
        }
        val info = CroppedImageInfo(width = cropped.width, height = cropped.height)
        cropped.recycle()
        return info
    }
}

data class CroppedImageInfo(
    val width: Int,
    val height: Int,
)
