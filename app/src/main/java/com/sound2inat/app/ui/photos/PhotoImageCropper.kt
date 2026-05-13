package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class PhotoImageCropper @Inject constructor() {
    fun cropCenterSquare(source: File, destination: File): CroppedImageInfo {
        require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
        destination.parentFile?.mkdirs()

        val bounds = decodeBounds(source)
        val cropSize = minOf(bounds.width, bounds.height)
        val left = ((bounds.width - cropSize) / 2).coerceAtLeast(0)
        val top = ((bounds.height - cropSize) / 2).coerceAtLeast(0)
        return decodeRegionToFile(
            source = source,
            destination = destination,
            left = left,
            top = top,
            size = cropSize,
        )
    }

    fun cropFromViewport(
        source: File,
        destination: File,
        request: PhotoCropRequest,
    ): CroppedImageInfo {
        require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
        destination.parentFile?.mkdirs()
        val bounds = decodeBounds(source)
        val frameSize = request.frameSizePx.coerceAtLeast(1)
        val totalScale = coverScale(bounds.width, bounds.height, frameSize) * request.scale.coerceAtLeast(1f)
        val cropSize = (frameSize / totalScale).toInt().coerceIn(1, minOf(bounds.width, bounds.height))
        val centerX = bounds.width / 2f - request.offsetX / totalScale
        val centerY = bounds.height / 2f - request.offsetY / totalScale
        val left = (centerX - cropSize / 2f).toInt().coerceIn(0, bounds.width - cropSize)
        val top = (centerY - cropSize / 2f).toInt().coerceIn(0, bounds.height - cropSize)
        return decodeRegionToFile(source, destination, left, top, cropSize)
    }

    private fun decodeBounds(source: File): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (BitmapFactory.decodeFile(source.absolutePath, options) == null && options.outWidth <= 0) {
            error("Could not decode bounds for ${source.absolutePath}")
        }
        return ImageBounds(width = options.outWidth, height = options.outHeight)
    }

    private fun coverScale(imageWidth: Int, imageHeight: Int, frameSizePx: Int): Float =
        maxOf(frameSizePx.toFloat() / imageWidth.toFloat(), frameSizePx.toFloat() / imageHeight.toFloat())

    private fun decodeRegionToFile(
        source: File,
        destination: File,
        left: Int,
        top: Int,
        size: Int,
    ): CroppedImageInfo {
        val rect = android.graphics.Rect(left, top, left + size, top + size)
        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val cropped = decoder.decodeRegion(rect, options)
            ?: error("Could not crop ${source.absolutePath}")
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

private data class ImageBounds(
    val width: Int,
    val height: Int,
)

data class CroppedImageInfo(
    val width: Int,
    val height: Int,
)
