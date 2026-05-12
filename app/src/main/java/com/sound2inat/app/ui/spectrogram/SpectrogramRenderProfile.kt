package com.sound2inat.app.ui.spectrogram

data class SpectrogramRenderProfile(
    val minFrequencyHz: Int,
    val maxFrequencyHz: Int,
    val noiseFloorMode: SpectrogramNoiseFloorMode,
    val noiseFloorPercentile: Float,
    val gateDb: Float,
    val displayRangeDb: Float,
    val gamma: Float,
    val maxInkArgb: Int,
    val smoothingTimeRadius: Int,
    val smoothingFrequencyRadius: Int,
) {
    companion object {
        val ReviewBird = SpectrogramRenderProfile(
            minFrequencyHz = 600,
            maxFrequencyHz = 10_000,
            noiseFloorMode = SpectrogramNoiseFloorMode.PER_FREQUENCY_PERCENTILE,
            noiseFloorPercentile = 20f,
            gateDb = 8f,
            displayRangeDb = 30f,
            gamma = 1.5f,
            maxInkArgb = MAX_INK_ARGB,
            smoothingTimeRadius = 1,
            smoothingFrequencyRadius = 1,
        )

        val LiveBird = SpectrogramRenderProfile(
            minFrequencyHz = 0,
            maxFrequencyHz = 10_000,
            noiseFloorMode = SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN,
            noiseFloorPercentile = 20f,
            gateDb = 6f,
            displayRangeDb = 30f,
            gamma = 1.5f,
            maxInkArgb = MAX_INK_ARGB,
            smoothingTimeRadius = 0,
            smoothingFrequencyRadius = 0,
        )

        val DebugFull = SpectrogramRenderProfile(
            minFrequencyHz = 0,
            maxFrequencyHz = 24_000,
            noiseFloorMode = SpectrogramNoiseFloorMode.NONE,
            noiseFloorPercentile = 20f,
            gateDb = 0f,
            displayRangeDb = 80f,
            gamma = 1f,
            maxInkArgb = MAX_INK_ARGB,
            smoothingTimeRadius = 0,
            smoothingFrequencyRadius = 0,
        )

        const val MAX_INK_ARGB = -14671840 // 0xFF202020
    }
}
