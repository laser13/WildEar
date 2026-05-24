package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import android.graphics.Matrix
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
                width = region.width,
                height = region.height,
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
        val bitmap = rotateBitmap(loadBitmap(source), request.rotationDegrees)
        val bounds = PhotoImageBounds(width = bitmap.width, height = bitmap.height)
        val region = viewportRegion(bounds, request)
        decodeRegionToFile(
            source = bitmap,
            destination = destination,
            left = region.left,
            top = region.top,
            width = region.width,
            height = region.height,
            region = region,
        )
    }

    suspend fun copyOriented(
        source: File,
        destination: File,
        rotationDegrees: Int = 0,
    ): CroppedImageInfo = withContext(Dispatchers.IO) {
        require(source.exists() && source.length() > 0) { "Source photo missing: ${source.absolutePath}" }
        destination.parentFile?.mkdirs()
        val bitmap = rotateBitmap(loadBitmap(source), rotationDegrees)
        FileOutputStream(destination).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                error("Could not write photo copy to ${destination.absolutePath}")
            }
        }
        val info = CroppedImageInfo(
            width = bitmap.width,
            height = bitmap.height,
            cropRegion = CropRegion(
                left = 0,
                top = 0,
                width = minOf(bitmap.width, bitmap.height),
                height = minOf(bitmap.width, bitmap.height),
            ),
        )
        bitmap.recycle()
        info
    }

    private fun coverScale(
        imageWidth: Int,
        imageHeight: Int,
        frameWidthPx: Int,
        frameHeightPx: Int,
    ): Float = maxOf(
        frameWidthPx.toFloat() / imageWidth.toFloat(),
        frameHeightPx.toFloat() / imageHeight.toFloat(),
    )

    protected open fun loadBitmap(source: File): Bitmap = decodeOrientedBitmap(source.absolutePath)

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply {
            postRotate(normalized.toFloat(), source.width / 2f, source.height / 2f)
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    internal fun centerSquareRegion(bounds: PhotoImageBounds): CropRegion {
        val cropSize = minOf(bounds.width, bounds.height)
        val left = ((bounds.width - cropSize) / 2).coerceAtLeast(0)
        val top = ((bounds.height - cropSize) / 2).coerceAtLeast(0)
        return CropRegion(left = left, top = top, width = cropSize, height = cropSize)
    }

    internal fun viewportRegion(bounds: PhotoImageBounds, request: PhotoCropRequest): CropRegion {
        val frameWidth = request.frameSizePx.coerceAtLeast(1)
        val frameHeight = request.frameHeightPx.coerceAtLeast(1)
        val totalScale = coverScale(bounds.width, bounds.height, frameWidth, frameHeight) *
            request.scale.coerceAtLeast(1f)
        val cropWidth = (frameWidth / totalScale).toInt().coerceIn(1, bounds.width)
        val cropHeight = (frameHeight / totalScale).toInt().coerceIn(1, bounds.height)
        val centerX = bounds.width / 2f - request.offsetX / totalScale
        val centerY = bounds.height / 2f - request.offsetY / totalScale
        val left = (centerX - cropWidth / 2f).toInt().coerceIn(0, bounds.width - cropWidth)
        val top = (centerY - cropHeight / 2f).toInt().coerceIn(0, bounds.height - cropHeight)
        return CropRegion(left = left, top = top, width = cropWidth, height = cropHeight)
    }

    private fun decodeRegionToFile(
        source: Bitmap,
        destination: File,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        region: CropRegion,
    ): CroppedImageInfo {
        val cropped = Bitmap.createBitmap(source, left, top, width, height)
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
    val width: Int,
    val height: Int,
) {
    val size: Int get() = width
}

data class CroppedImageInfo(
    val width: Int,
    val height: Int,
    val cropRegion: CropRegion,
)
