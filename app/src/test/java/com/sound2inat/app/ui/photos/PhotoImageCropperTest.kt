package com.sound2inat.app.ui.photos

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftImageEntity
import org.junit.Test

class PhotoImageCropperTest {
    @Test
    fun `center crop on oriented bitmap keeps the middle square`() {
        val cropper = PhotoImageCropper()

        val region = cropper.centerSquareRegion(
            PhotoImageBounds(width = 2, height = 4),
        )

        assertThat(region).isEqualTo(
            CropRegion(
                left = 0,
                top = 1,
                width = 2,
                height = 2,
            ),
        )
    }

    @Test
    fun `viewport crop maps the frame back into oriented source coordinates`() {
        val cropper = PhotoImageCropper()

        val region = cropper.viewportRegion(
            bounds = PhotoImageBounds(width = 2, height = 4),
            request = PhotoCropRequest(
                frameSizePx = 100,
                frameHeightPx = 100,
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
            ),
        )

        assertThat(region).isEqualTo(
            CropRegion(
                left = 0,
                top = 1,
                width = 2,
                height = 2,
            ),
        )
    }

    @Test
    fun `viewport crop honors rectangular frame dimensions`() {
        val cropper = PhotoImageCropper()

        val region = cropper.viewportRegion(
            bounds = PhotoImageBounds(width = 2, height = 4),
            request = PhotoCropRequest(
                frameSizePx = 50,
                frameHeightPx = 100,
                viewportWidthPx = 100,
                viewportHeightPx = 100,
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
            ),
        )

        assertThat(region).isEqualTo(
            CropRegion(
                left = 0,
                top = 1,
                width = 1,
                height = 2,
            ),
        )
    }

    @Test
    fun `crop frame changes shape inside the same original-aspect preview`() {
        assertThat(
            cropFrameSize(
                previewSize = IntSize(width = 300, height = 400),
                mode = PhotoCropMode.Original,
            ),
        ).isEqualTo(IntSize(width = 300, height = 400))

        assertThat(
            cropFrameSize(
                previewSize = IntSize(width = 300, height = 400),
                mode = PhotoCropMode.Square,
            ),
        ).isEqualTo(IntSize(width = 300, height = 300))
    }

    @Test
    fun `viewport crop applies requested rotation before calculating the region`() {
        val rotatedBounds = rotatedPhotoBounds(PhotoImageBounds(width = 2, height = 4), 90)

        assertThat(rotatedBounds).isEqualTo(PhotoImageBounds(width = 4, height = 2))
        assertThat(normalizeRotationDegrees(-270)).isEqualTo(90)
    }

    @Test
    fun `crop dialog renders with the same cover model used by crop math`() {
        val source = java.io.File("src/main/java/com/sound2inat/app/ui/photos/PhotoReviewScreen.kt").readText()
        val dialogStart = source.indexOf("private fun PhotoImageDialog")
        val dialogEnd = source.indexOf("private val UTC_FORMATTER")
        val dialogSource = source.substring(dialogStart, dialogEnd)

        assertThat(dialogSource).contains("SingleChoiceSegmentedButtonRow")
        assertThat(dialogSource).contains("photo_crop_mode_original")
        assertThat(dialogSource).contains("photo_crop_mode_square")
        assertThat(dialogSource).contains("clipToBounds()")
        assertThat(dialogSource).contains("ContentScale.Crop")
        assertThat(dialogSource).doesNotContain("ContentScale.Fit")
        assertThat(dialogSource).contains("coerceIn(1f, 6f)")
        assertThat(dialogSource).doesNotContain("PhotoCropMode.Square -> 1f")
        assertThat(dialogSource).contains("photo_crop_apply_original")
        assertThat(dialogSource).contains("photo_crop_apply_square")
    }

    @Test
    fun `crop dialog keeps controls compact and removes read-only metadata chips`() {
        val source = java.io.File("src/main/java/com/sound2inat/app/ui/photos/PhotoReviewScreen.kt").readText()
        val dialogStart = source.indexOf("private fun PhotoImageDialog")
        val dialogEnd = source.indexOf("private val UTC_FORMATTER")
        val dialogSource = source.substring(dialogStart, dialogEnd)

        assertThat(dialogSource).doesNotContain("The source keeps its original shape")
        assertThat(dialogSource).doesNotContain("Frame 1:1")
        assertThat(dialogSource).doesNotContain("formatCropSourceLabel")
        assertThat(dialogSource).doesNotContain("AssistChip(")
    }

    @Test
    fun `uncropped photo opens in original mode`() {
        val image = PhotoDraftImageEntity(
            id = "p1",
            photoDraftId = "d1",
            originalPhotoPath = "/tmp/original.jpg",
            photoPath = "/tmp/original.jpg",
            cropLeftPx = null,
            cropTopPx = null,
            cropSizePx = null,
            takenAtUtcMs = 0L,
            sortOrder = 0,
            width = 3000,
            height = 4000,
            mimeType = "image/jpeg",
        )

        assertThat(initialCropMode(image)).isEqualTo(PhotoCropMode.Original)
    }

    @Test
    fun `saved crop reopens in square mode`() {
        val image = PhotoDraftImageEntity(
            id = "p1",
            photoDraftId = "d1",
            originalPhotoPath = "/tmp/original.jpg",
            photoPath = "/tmp/cropped.jpg",
            cropLeftPx = 10,
            cropTopPx = 20,
            cropSizePx = 200,
            takenAtUtcMs = 0L,
            sortOrder = 0,
            width = 200,
            height = 200,
            mimeType = "image/jpeg",
        )

        assertThat(initialCropMode(image)).isEqualTo(PhotoCropMode.Square)
    }
}
