package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewAudioProcessorTest {

    @Test
    fun `original config returns an unchanged copy`() {
        val samples = shortArrayOf(0, 1000, -1000, 3200)
        val processed = ReviewAudioProcessor.process(samples, 48_000, ReviewAudioProcessingConfig.Original)

        assertThat(processed).isEqualTo(samples)
        assertThat(processed).isNotSameInstanceAs(samples)
    }

    @Test
    fun `gain increases output amplitude`() {
        val samples = shortArrayOf(0, 1000, -1000, 2000)
        val processed = ReviewAudioProcessor.process(
            samples = samples,
            sampleRateHz = 48_000,
            config = ReviewAudioProcessingConfig(
                preset = ReviewAudioProcessingConfig.Preset.CUSTOM,
                gainDb = 6f,
                normalizePeak = false,
            ),
        )

        assertThat(processed.maxOrNull()!!.toInt()).isGreaterThan(samples.maxOrNull()!!.toInt())
        assertThat(processed.minOrNull()!!.toInt()).isLessThan(samples.minOrNull()!!.toInt())
    }

    @Test
    fun `cache suffix preserves decimal gain precision`() {
        val config = ReviewAudioProcessingConfig(
            preset = ReviewAudioProcessingConfig.Preset.CUSTOM,
            highPassHz = 600,
            gainDb = 7.5f,
            normalizePeak = true,
        )

        assertThat(config.cacheSuffix()).contains("gain_75")
        assertThat(config.cacheSuffix()).contains("hp_600")
        assertThat(config.cacheSuffix()).contains("norm_true")
    }
}
