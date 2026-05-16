package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ReviewSpectrogramDisplayPlaneTest {
    private fun sineWave(freqHz: Double, sampleRateHz: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (sin(2 * PI * freqHz * i / sampleRateHz) * 0.5).toFloat()
        }
        return out
    }

    private fun sampleMatrix(): ReviewSpectrogramMatrix {
        val config = ReviewSpectrogramAnalysisConfig.from(sampleRateHz = 48_000)
        val samples = sineWave(1_000.0, config.sampleRateHz, config.sampleRateHz * 3)
        return ReviewSpectrogramAnalyzer().analyze(samples, config)
    }

    @Test
    fun `palette changes can reuse the same display plane`() {
        val matrix = sampleMatrix()
        val plane = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK),
        ).buildDisplayPlane(matrix)

        val ink = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK),
        ).render(plane)
        val viridis = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.VIRIDIS),
        ).render(plane)

        assertThat(ink).isNotEqualTo(viridis)
    }

    @Test
    fun `display range changes the display plane`() {
        val matrix = sampleMatrix()
        val full = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.FULL),
        ).buildDisplayPlane(matrix)
        val bird = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.BIRDNET_BIRD),
        ).buildDisplayPlane(matrix)

        assertThat(full).isNotEqualTo(bird)
    }
}
