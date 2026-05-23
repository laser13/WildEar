package com.sound2inat.app.ui.photos

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

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
                size = 2,
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
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
            ),
        )

        assertThat(region).isEqualTo(
            CropRegion(
                left = 0,
                top = 1,
                size = 2,
            ),
        )
    }

    @Test
    fun `crop dialog renders with the same cover model used by crop math`() {
        val source = File("src/main/java/com/sound2inat/app/ui/photos/PhotoReviewScreen.kt").readText()
        val dialogStart = source.indexOf("private fun PhotoImageDialog")
        val dialogEnd = source.indexOf("private val UTC_FORMATTER")
        val dialogSource = source.substring(dialogStart, dialogEnd)

        assertThat(dialogSource).contains("contentScale = ContentScale.Crop")
        assertThat(dialogSource).doesNotContain("contentScale = ContentScale.Fit")
    }

    @Test
    fun `crop dialog keeps controls compact and removes read-only metadata chips`() {
        val source = File("src/main/java/com/sound2inat/app/ui/photos/PhotoReviewScreen.kt").readText()
        val dialogStart = source.indexOf("private fun PhotoImageDialog")
        val dialogEnd = source.indexOf("private val UTC_FORMATTER")
        val dialogSource = source.substring(dialogStart, dialogEnd)

        assertThat(dialogSource).doesNotContain("The source keeps its original shape")
        assertThat(dialogSource).doesNotContain("Frame 1:1")
        assertThat(dialogSource).doesNotContain("formatCropSourceLabel")
        assertThat(dialogSource).doesNotContain("AssistChip(")
    }
}
