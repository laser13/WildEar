package com.sound2inat.audio

data class SpectrogramRenderProfile(
    val minFrequencyHz: Int,
    val maxFrequencyHz: Int,
    val gateDb: Float,
    val displayRangeDb: Float,
    val gamma: Float,
    val maxInkArgb: Int,
    val smoothingTimeRadius: Int,
    val smoothingFrequencyRadius: Int,
) {
    companion object {
        val LiveBird = SpectrogramRenderProfile(
            minFrequencyHz = 500,
            maxFrequencyHz = 10_000,
            gateDb = 0f,
            displayRangeDb = 40f,
            gamma = 1.25f,
            maxInkArgb = MAX_INK_ARGB,
            smoothingTimeRadius = 2,
            smoothingFrequencyRadius = 2,
        )

        const val MAX_INK_ARGB = -14671840 // 0xFF202020
    }
}
