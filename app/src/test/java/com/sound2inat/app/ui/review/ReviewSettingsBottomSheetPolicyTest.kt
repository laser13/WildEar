package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import org.junit.Test

class ReviewSettingsBottomSheetPolicyTest {

    @Test
    fun `visual settings apply immediately`() {
        assertThat(ReviewSettingsTab.Visual.appliesImmediately()).isTrue()
        assertThat(ReviewSettingsTab.Audio.appliesImmediately()).isFalse()
    }

    @Test
    fun `only audio tab shows confirmation buttons`() {
        assertThat(ReviewSettingsTab.Audio.showsConfirmationButtons()).isTrue()
        assertThat(ReviewSettingsTab.Visual.showsConfirmationButtons()).isFalse()
    }

    @Test
    fun `visual config update returns a new profile with the changed spectrogram settings`() {
        val updatedConfig = ReviewSpectrogramConfig.BirdDefault.copy(
            displayRange = SpectrogramDisplayRange.FULL,
            palette = SpectrogramPalette.MAGMA,
            gainDb = 10f,
        )

        val updatedProfile = ReviewProcessingProfile.Default.withSpectrogramConfig(updatedConfig)

        assertThat(updatedProfile.spectrogramConfig).isEqualTo(updatedConfig)
        assertThat(updatedProfile.audioProcessingConfig).isEqualTo(ReviewAudioProcessingConfig.Original)
    }
}
