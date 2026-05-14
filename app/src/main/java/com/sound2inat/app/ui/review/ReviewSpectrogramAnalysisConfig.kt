package com.sound2inat.app.ui.review

/**
 * Analysis-level parameters that define the reusable spectrogram matrix.
 * Changing any field requires rebuilding the matrix cache.
 */
data class ReviewSpectrogramAnalysisConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val displayHeightBins: Int = 256,
    val minFrequencyHz: Int,
    val maxFrequencyHz: Int,
    val sampleRateHz: Int,
    val version: Int = CACHE_FORMAT_VERSION,
) {
    companion object {
        const val CACHE_FORMAT_VERSION: Int = 2

        @Suppress("UNUSED_PARAMETER")
        fun from(
            displayRange: SpectrogramDisplayRange,
            sampleRateHz: Int,
        ): ReviewSpectrogramAnalysisConfig = ReviewSpectrogramAnalysisConfig(
            minFrequencyHz = 0,
            maxFrequencyHz = sampleRateHz / 2,
            sampleRateHz = sampleRateHz,
        )
    }
}
