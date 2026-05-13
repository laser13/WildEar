package com.sound2inat.app.ui.review

/**
 * Shared review profile tying together what the user hears and what they see.
 *
 * The current profile is intended to become the single source of truth for the
 * review card: when the profile changes, playback, reanalysis, upload and the
 * displayed spectrogram should all reflect the same state.
 */
data class ReviewProcessingProfile(
    val spectrogramConfig: ReviewSpectrogramConfig = ReviewSpectrogramConfig.BirdDefault,
    val audioProcessingConfig: ReviewAudioProcessingConfig = ReviewAudioProcessingConfig.Original,
) {
    val isDefault: Boolean
        get() = this == Default

    fun reset(): ReviewProcessingProfile = Default

    companion object {
        val Default = ReviewProcessingProfile()
    }
}
