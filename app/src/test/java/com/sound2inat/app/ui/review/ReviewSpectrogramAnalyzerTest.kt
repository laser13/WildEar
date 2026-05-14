package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ReviewSpectrogramAnalyzerTest {
    private fun sineWave(freqHz: Double, sampleRateHz: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (sin(2 * PI * freqHz * i / sampleRateHz) * 0.5).toFloat()
        }
        return out
    }

    @Test
    fun `short audio returns empty matrix`() {
        val analyzer = ReviewSpectrogramAnalyzer()
        val config = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)

        val matrix = analyzer.analyze(FloatArray(2_047), config)

        assertThat(matrix.frames).isEqualTo(0)
        assertThat(matrix.values).hasLength(config.displayHeightBins)
        assertThat(matrix.values.first()).hasLength(0)
    }

    @Test
    fun `same input and config are deterministic`() {
        val analyzer = ReviewSpectrogramAnalyzer()
        val config = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)
        val samples = sineWave(1_000.0, config.sampleRateHz, config.sampleRateHz * 3)

        val first = analyzer.analyze(samples, config)
        val second = analyzer.analyze(samples, config)

        assertThat(first.frames).isEqualTo(second.frames)
        assertThat(first.values.map { it.toList() }).isEqualTo(second.values.map { it.toList() })
    }

    @Test
    fun `matrix dimensions match display bins by frames`() {
        val analyzer = ReviewSpectrogramAnalyzer()
        val config = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)
        val samples = sineWave(1_000.0, config.sampleRateHz, config.sampleRateHz * 3)

        val matrix = analyzer.analyze(samples, config)

        assertThat(matrix.values).hasLength(config.displayHeightBins)
        assertThat(matrix.values.all { it.size == matrix.frames }).isTrue()
    }

    @Test
    fun `changing display range changes the matrix content`() {
        val analyzer = ReviewSpectrogramAnalyzer()
        val samples = sineWave(1_000.0, 48_000, 48_000 * 3)
        val full = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.FULL, sampleRateHz = 48_000)
        val bird = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)

        val fullMatrix = analyzer.analyze(samples, full)
        val birdMatrix = analyzer.analyze(samples, bird)

        assertThat(fullMatrix).isNotEqualTo(birdMatrix)
    }
}
