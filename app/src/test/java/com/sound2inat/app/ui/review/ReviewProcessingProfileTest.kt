package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile
import org.junit.Test

class ReviewProcessingProfileTest {

    @Test
    fun `default review processing profile follows live recording defaults`() {
        val profile = ReviewProcessingProfile.Default

        assertThat(profile.isDefault).isTrue()
        assertThat(profile.spectrogramConfig).isEqualTo(ReviewSpectrogramConfig.BirdDefault)
        // Null means "follow live defaults" — the resolved value lives behind the
        // effective* accessors.
        assertThat(profile.spectrogramConfig.palette).isNull()
        assertThat(profile.spectrogramConfig.gainDb).isNull()
        assertThat(profile.spectrogramConfig.effectivePalette).isEqualTo(SpectrogramPalette.INK)
        assertThat(profile.spectrogramConfig.effectiveGainDb).isEqualTo(0f)
        val live = SpectrogramRenderProfile.LiveBird
        assertThat(profile.spectrogramConfig.effectiveRangeSpec.fMinHz).isEqualTo(live.minFrequencyHz)
        assertThat(profile.spectrogramConfig.effectiveRangeSpec.fMaxHz).isEqualTo(live.maxFrequencyHz)
    }

    @Test
    fun `reset returns the default profile`() {
        val custom = ReviewProcessingProfile(
            spectrogramConfig = ReviewSpectrogramConfig.BirdDefault.copy(gainDb = 10f),
        )

        assertThat(custom.reset()).isEqualTo(ReviewProcessingProfile.Default)
    }

    @Test
    fun `review ui state defaults to the default profile`() {
        val state = ReviewUiState(draftId = "draft-1")

        assertThat(state.processingProfile).isEqualTo(ReviewProcessingProfile.Default)
    }
}
