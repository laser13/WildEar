package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.LiveDefaults
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import org.junit.Test

class ReviewProcessingProfileTest {

    @Test
    fun `default review processing profile follows live recording defaults`() {
        val profile = ReviewProcessingProfile.Default

        assertThat(profile.isDefault).isTrue()
        assertThat(profile.audioProcessingConfig).isEqualTo(ReviewAudioProcessingConfig.Original)
        assertThat(profile.spectrogramConfig).isEqualTo(ReviewSpectrogramConfig.BirdDefault)
        // Null means "follow live defaults" — the resolved value lives behind the
        // effective* accessors.
        assertThat(profile.spectrogramConfig.displayRange).isNull()
        assertThat(profile.spectrogramConfig.palette).isNull()
        assertThat(profile.spectrogramConfig.gainDb).isNull()
        assertThat(profile.spectrogramConfig.effectiveRangeSpec).isEqualTo(LiveDefaults.displayRange())
        assertThat(profile.spectrogramConfig.effectivePalette).isEqualTo(SpectrogramPalette.INK)
        assertThat(profile.spectrogramConfig.effectiveGainDb).isEqualTo(0f)
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
