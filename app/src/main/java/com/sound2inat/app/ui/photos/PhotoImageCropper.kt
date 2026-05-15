package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

open class PhotoImageCropper @Inject constructor() {
    suspend fun cropCenterSquare(source: File, destination: File): CroppedImageInfo =
        withContext(Dispatchers.IO) {
            require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
            destination.parentFile?.mkdirs()

            val bitmap = loadBitmap(source)
            val bounds = PhotoImageBounds(width = bitmap.width, height = bitmap.height)
            val region = centerSquareRegion(bounds)
            decodeRegionToFile(
                source = bitmap,
                destination = destination,
                left = region.left,
                top = region.top,
                size = region.size,
                region = region,
            )
        }

    suspend fun cropFromViewport(
        source: File,
        destination: File,
        request: PhotoCropRequest,
    ): CroppedImageInfo = withContext(Dispatchers.IO) {
        require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
        destination.parentFile?.mkdirs()
        val bitmap = loadBitmap(source)
        val bounds = PhotoImageBounds(width = bitmap.width, height = bitmap.height)
        val region = viewportRegion(bounds, request)
        decodeRegionToFile(
            source = bitmap,
            destination = destination,
            left = region.left,
            top = region.top,
            size = region.size,
            region = region,
        )
    }

    private fun coverScale(imageWidth: Int, imageHeight: Int, frameSizePx: Int): Float =
        maxOf(frameSizePx.toFloat() / imageWidth.toFloat(), frameSizePx.toFloat() / imageHeight.toFloat())

    protected open fun loadBitmap(source: File): Bitmap = decodeOrientedBitmap(source.absolutePath)

    internal fun centerSquareRegion(bounds: PhotoImageBounds): CropRegion {
        val cropSize = minOf(bounds.width, bounds.height)
        val left = ((bounds.width - cropSize) / 2).coerceAtLeast(0)
        val top = ((bounds.height - cropSize) / 2).coerceAtLeast(0)
        return CropRegion(left = left, top = top, size = cropSize)
    }

    internal fun viewportRegion(bounds: PhotoImageBounds, request: PhotoCropRequest): CropRegion {
        val frameSize = request.frameSizePx.coerceAtLeast(1)
        val totalScale = coverScale(bounds.width, bounds.height, frameSize) * request.scale.coerceAtLeast(1f)
        val cropSize = (frameSize / totalScale).toInt().coerceIn(1, minOf(bounds.width, bounds.height))
        val centerX = bounds.width / 2f - request.offsetX / totalScale
        val centerY = bounds.height / 2f - request.offsetY / totalScale
        val left = (centerX - cropSize / 2f).toInt().coerceIn(0, bounds.width - cropSize)
        val top = (centerY - cropSize / 2f).toInt().coerceIn(0, bounds.height - cropSize)
        return CropRegion(left = left, top = top, size = cropSize)
    }

    private fun decodeRegionToFile(
        source: Bitmap,
        destination: File,
        left: Int,
        top: Int,
        size: Int,
        region: CropRegion,
    ): CroppedImageInfo {
        val cropped = Bitmap.createBitmap(source, left, top, size, size)
        FileOutputStream(destination).use { out ->
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                error("Could not write cropped photo to ${destination.absolutePath}")
            }
        }
        val info = CroppedImageInfo(width = cropped.width, height = cropped.height, cropRegion = region)
        cropped.recycle()
        if (source !== cropped) {
            source.recycle()
        }
        return info
    }
}

data class CropRegion(
    val left: Int,
    val top: Int,
    val size: Int,
)

data class CroppedImageInfo(
    val width: Int,
    val height: Int,
    val cropRegion: CropRegion,
)
