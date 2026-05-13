package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewProcessingProfileTest {

    @Test
    fun `default review processing profile uses BirdNET review defaults and Original audio`() {
        val profile = ReviewProcessingProfile.Default

        assertThat(profile.isDefault).isTrue()
        assertThat(profile.audioProcessingConfig).isEqualTo(ReviewAudioProcessingConfig.Original)
        assertThat(profile.spectrogramConfig).isEqualTo(ReviewSpectrogramConfig.BirdDefault)
        assertThat(profile.spectrogramConfig.displayRange.fMinHz).isEqualTo(1_000)
        assertThat(profile.spectrogramConfig.displayRange.fMaxHz).isEqualTo(12_000)
        assertThat(profile.spectrogramConfig.gainDb).isEqualTo(0f)
        assertThat(profile.spectrogramConfig.lowPercentile).isEqualTo(5f)
        assertThat(profile.spectrogramConfig.highPercentile).isEqualTo(99f)
    }

    @Test
    fun `reset returns the default profile`() {
        val custom = ReviewProcessingProfile(
            spectrogramConfig = ReviewSpectrogramConfig.BirdDefault.copy(gainDb = 10f),
            audioProcessingConfig = ReviewAudioProcessingConfig.BirdClean,
        )

        assertThat(custom.reset()).isEqualTo(ReviewProcessingProfile.Default)
    }

    @Test
    fun `review ui state defaults to the default profile`() {
        val state = ReviewUiState(draftId = "draft-1")

        assertThat(state.processingProfile).isEqualTo(ReviewProcessingProfile.Default)
        assertThat(state.audioProcessingConfig).isEqualTo(ReviewAudioProcessingConfig.Original)
    }
}
