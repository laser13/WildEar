package com.sound2inat.app.ui.photos

import com.google.common.truth.Truth.assertThat
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
}
