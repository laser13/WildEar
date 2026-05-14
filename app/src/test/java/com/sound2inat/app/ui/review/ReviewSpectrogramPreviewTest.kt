package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewSpectrogramPreviewTest {
    @Test
    fun `fromRows flattens pixels row-major`() {
        val preview = ReviewSpectrogramPreview.fromRows(
            arrayOf(
                intArrayOf(0x11, 0x12),
                intArrayOf(0x21, 0x22),
            ),
        )

        assertThat(preview.width).isEqualTo(2)
        assertThat(preview.height).isEqualTo(2)
        assertThat(preview.argb).isEqualTo(intArrayOf(0x11, 0x12, 0x21, 0x22))
    }

    @Test
    fun `fromRows rejects ragged rows`() {
        val thrown = try {
            ReviewSpectrogramPreview.fromRows(
                arrayOf(
                    intArrayOf(0x11, 0x12),
                    intArrayOf(0x21),
                ),
            )
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }

        assertThat(thrown).hasMessageThat().contains("same width")
    }
}
