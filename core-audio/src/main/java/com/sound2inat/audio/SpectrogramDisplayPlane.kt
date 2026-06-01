package com.sound2inat.audio

/**
 * Normalized, smoothed display-space spectrogram values.
 *
 * This sits between the reusable analysis matrix and the final colored
 * preview pixels. Palette changes can reuse the same plane and only remap
 * colors.
 */
data class SpectrogramDisplayPlane(
    val width: Int,
    val height: Int,
    val values: Array<FloatArray>,
) {
    init {
        require(width >= 0) { "width must be non-negative" }
        require(height >= 0) { "height must be non-negative" }
        require(values.size == height) { "Unexpected number of display rows" }
        values.forEach { row -> require(row.size == width) { "Unexpected display width" } }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrogramDisplayPlane) return false
        return width == other.width &&
            height == other.height &&
            values.contentDeepEquals(other.values)
    }

    override fun hashCode(): Int =
        31 * (31 * width + height) + values.contentDeepHashCode()
}
