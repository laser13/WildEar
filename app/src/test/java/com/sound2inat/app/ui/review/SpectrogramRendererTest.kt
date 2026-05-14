package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrogramRendererTest {
    private fun sineWave(freqHz: Double, sampleRateHz: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (sin(2 * PI * freqHz * i / sampleRateHz) * 0.5).toFloat()
        }
        return out
    }

    private fun sampleMatrix(
        displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.BIRDNET_BIRD,
    ): ReviewSpectrogramMatrix {
        val config = ReviewSpectrogramAnalysisConfig.from(displayRange, sampleRateHz = 48_000)
        val samples = sineWave(1_000.0, config.sampleRateHz, config.sampleRateHz * 3)
        return ReviewSpectrogramAnalyzer().analyze(samples, config)
    }

    private fun midToneMatrix(): ReviewSpectrogramMatrix {
        val config = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)
        val values = Array(config.displayHeightBins) { row ->
            FloatArray(32) { frame ->
                ((row + frame) % 32).toFloat() / 32f
            }
        }
        return ReviewSpectrogramMatrix(config = config, frames = 32, values = values)
    }

    @Test
    fun `rendering an empty matrix returns empty pixels`() {
        val config = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)
        val emptyMatrix = ReviewSpectrogramMatrix(
            config = config,
            frames = 0,
            values = Array(config.displayHeightBins) { FloatArray(0) },
        )

        val pixels = SpectrogramRenderer().render(emptyMatrix)

        assertThat(pixels).isEmpty()
    }

    @Test
    fun `render produces shared live-style height and width capped to target`() {
        val renderer = SpectrogramRenderer(targetWidth = 64)
        val pixels = renderer.render(sampleMatrix())

        assertThat(pixels.size).isEqualTo(256)
        assertThat(pixels[0].size).isEqualTo(64)
    }

    @Test
    fun `render does not mutate the input matrix`() {
        val renderer = SpectrogramRenderer(config = ReviewSpectrogramConfig.BirdDefault.copy(gainDb = 6f))
        val matrix = sampleMatrix()
        val original = matrix.values.map { it.toList() }

        renderer.render(matrix)

        assertThat(matrix.values.map { it.toList() }).isEqualTo(original)
    }

    @Test
    fun `palette changes pixels without changing matrix`() {
        val matrix = sampleMatrix()
        val ink = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK),
        ).render(matrix)
        val viridis = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.VIRIDIS),
        ).render(matrix)

        assertThat(ink).isNotEqualTo(viridis)
    }

    @Test
    fun `gain is absorbed by normalization when noise floor is disabled`() {
        val matrix = midToneMatrix()
        val neutral = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(noiseFloorMode = SpectrogramNoiseFloorMode.NONE, gainDb = 0f),
        ).render(matrix)
        val boosted = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(noiseFloorMode = SpectrogramNoiseFloorMode.NONE, gainDb = 12f),
        ).render(matrix)

        assertThat(neutral).isEqualTo(boosted)
    }

    @Test
    fun `per frequency noise floor changes pixels without mutating the matrix`() {
        val matrix = sampleMatrix()
        val noNoiseFloor = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(noiseFloorMode = SpectrogramNoiseFloorMode.NONE),
        ).render(matrix)
        val median = SpectrogramRenderer(
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(noiseFloorMode = SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN),
        ).render(matrix)

        assertThat(noNoiseFloor).isNotEqualTo(median)
        val original = sampleMatrix()
        assertThat(matrix.values.map { it.toList() }).isEqualTo(original.values.map { it.toList() })
    }

    @Test
    fun `top-dB clamp preserves values within range and raises floor`() {
        val values = FloatArray(201) { i -> (i - 200).toFloat() }
        val clamped = SpectrogramRenderer.applyTopDbClamp(values, topDb = 75f)
        val maxClamped = clamped.max()
        val minClamped = clamped.min()
        assertThat(maxClamped).isEqualTo(0f)
        assertThat(minClamped).isEqualTo(-75f)
        assertThat(maxClamped - minClamped).isEqualTo(75f)
    }

    @Test
    fun `percentile normalize clamps and scales values`() {
        val values = floatArrayOf(0f, 10f, 20f, 30f, 40f)
        val normalized = SpectrogramRenderer.percentileNormalize(values, lowPercentile = 0f, highPercentile = 100f)

        assertThat(normalized.first()).isEqualTo(0f)
        assertThat(normalized.last()).isEqualTo(1f)
    }
}
