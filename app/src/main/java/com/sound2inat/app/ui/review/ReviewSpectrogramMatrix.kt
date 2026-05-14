package com.sound2inat.app.ui.review

/**
 * Reusable frequency-by-time spectrogram matrix used as the source for visual rendering.
 * Values are stored as [frequencyBin][frame].
 */
data class ReviewSpectrogramMatrix(
    val config: ReviewSpectrogramAnalysisConfig,
    val frames: Int,
    val values: Array<FloatArray>,
) {
    init {
        require(frames >= 0) { "frames must be non-negative" }
        require(values.size == config.displayHeightBins) { "Unexpected number of frequency bins" }
        values.forEach { row -> require(row.size == frames) { "Unexpected frame count" } }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReviewSpectrogramMatrix) return false
        return config == other.config &&
            frames == other.frames &&
            values.contentDeepEquals(other.values)
    }

    override fun hashCode(): Int = 31 * (31 * config.hashCode() + frames) + values.contentDeepHashCode()
}
