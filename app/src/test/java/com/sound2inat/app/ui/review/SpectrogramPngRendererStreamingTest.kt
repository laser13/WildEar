package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.SpectrogramPalette
import com.sound2inat.audio.SpectrogramPngRenderer
import org.junit.Test
import kotlin.math.sin

class SpectrogramPngRendererStreamingTest {

    private fun tone(samples: Int, sampleRateHz: Int, freqHz: Double): FloatArray =
        FloatArray(samples) { i -> sin(2.0 * Math.PI * freqHz * i / sampleRateHz).toFloat() * 0.5f }

    @Test
    fun `streaming render equals full-buffer render on short tone`() {
        val sampleRate = 48_000
        // ~0.5s — enough to span many FFT hops but tiny in memory.
        val samples = tone(24_000, sampleRate, freqHz = 2_000.0)

        val full = SpectrogramPngRenderer.render(
            samples = samples,
            sampleRateHz = sampleRate,
            palette = SpectrogramPalette.INK,
            contrastDb = 0f,
        )

        // Feed the same samples in small, ragged blocks via the streaming entry point.
        val streamed = SpectrogramPngRenderer.renderStreaming(
            sampleRateHz = sampleRate,
            palette = SpectrogramPalette.INK,
            contrastDb = 0f,
        ) { onBlock ->
            var i = 0
            val blockSizes = intArrayOf(1000, 333, 4096, 7, 16_384)
            var bi = 0
            while (i < samples.size) {
                val n = minOf(blockSizes[bi % blockSizes.size], samples.size - i)
                onBlock(samples.copyOfRange(i, i + n))
                i += n
                bi++
            }
        }

        assertThat(streamed.displayPlane.width).isEqualTo(full.displayPlane.width)
        assertThat(streamed.displayPlane.height).isEqualTo(full.displayPlane.height)
        // values is indexed [y][x] where values.size == height.
        // Plane values must match bit-for-bit: same STFT state, same pipeline.
        for (y in 0 until full.displayPlane.height) {
            assertThat(streamed.displayPlane.values[y].contentEquals(full.displayPlane.values[y]))
                .isTrue()
        }
        assertThat(streamed.preview.argb.contentEquals(full.preview.argb)).isTrue()
    }

    @Test
    fun `streaming render on empty stream yields empty plane`() {
        val streamed = SpectrogramPngRenderer.renderStreaming(
            sampleRateHz = 48_000,
            palette = SpectrogramPalette.INK,
            contrastDb = 0f,
        ) { /* emit nothing */ }
        assertThat(streamed.displayPlane.width).isEqualTo(0)
        assertThat(streamed.preview.argb).hasLength(0)
    }
}
