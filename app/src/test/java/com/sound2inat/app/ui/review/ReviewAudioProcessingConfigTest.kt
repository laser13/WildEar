package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewAudioProcessingConfigTest {

    @Test
    fun `high pass options are discrete and ordered`() {
        assertThat(reviewHighPassOptions()).containsExactly(
            null,
            300,
            600,
            1_000,
            1_600,
        ).inOrder()
    }

    @Test
    fun `gain options are discrete and ordered`() {
        assertThat(reviewGainOptions()).containsExactly(
            -12f,
            -6f,
            0f,
            6f,
            12f,
        ).inOrder()
    }

    @Test
    fun `customization helpers switch the preset to custom`() {
        val config = ReviewAudioProcessingConfig.BirdClean

        assertThat(config.withHighPass(300).preset).isEqualTo(ReviewAudioProcessingConfig.Preset.CUSTOM)
        assertThat(config.withGain(6f).preset).isEqualTo(ReviewAudioProcessingConfig.Preset.CUSTOM)
        assertThat(config.withNormalize(false).preset).isEqualTo(ReviewAudioProcessingConfig.Preset.CUSTOM)
    }
}
