package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette

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

    @Test
    fun `fromDisplayPlane renders a preview from the plane`() {
        val config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK)
        val preview = ReviewSpectrogramPreview.fromDisplayPlane(
            ReviewSpectrogramDisplayPlane(
                width = 2,
                height = 2,
                values = arrayOf(
                    floatArrayOf(0f, 0.5f),
                    floatArrayOf(0.5f, 1f),
                ),
            ),
            config,
        )

        assertThat(preview.width).isEqualTo(2)
        assertThat(preview.height).isEqualTo(2)
        assertThat(preview.argb).hasLength(4)
    }

    @Test
    fun `equal previews compare pixel content not array identity`() {
        val first = ReviewSpectrogramPreview.fromRows(
            arrayOf(
                intArrayOf(0x11, 0x12),
                intArrayOf(0x21, 0x22),
            ),
        )
        val second = ReviewSpectrogramPreview.fromRows(
            arrayOf(
                intArrayOf(0x11, 0x12),
                intArrayOf(0x21, 0x22),
            ),
        )

        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    @Test
    fun `constructor clones the source pixel array`() {
        val source = intArrayOf(0x11, 0x12, 0x21, 0x22)
        val preview = ReviewSpectrogramPreview(width = 2, height = 2, argb = source)

        source[0] = 0x99

        assertThat(preview.argb).isEqualTo(intArrayOf(0x11, 0x12, 0x21, 0x22))
    }
}
