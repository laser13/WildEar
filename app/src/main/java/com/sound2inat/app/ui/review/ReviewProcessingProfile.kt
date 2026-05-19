package com.sound2inat.app.ui.review

/**
 * Shared review profile carrying the spectrogram visual config. Audio is always
 * played back as recorded — no post-recording processing surface remains.
 */
data class ReviewProcessingProfile(
    val spectrogramConfig: ReviewSpectrogramConfig = ReviewSpectrogramConfig.BirdDefault,
) {
    val isDefault: Boolean
        get() = this == Default

    fun reset(): ReviewProcessingProfile = Default

    companion object {
        val Default = ReviewProcessingProfile()
    }
}
