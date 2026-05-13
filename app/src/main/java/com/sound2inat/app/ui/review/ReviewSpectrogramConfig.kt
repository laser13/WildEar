package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile

data class ReviewSpectrogramConfig(
    val displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.BIRDNET_BIRD,
    val palette: SpectrogramPalette = SpectrogramPalette.VIRIDIS,
    val gainDb: Float = 0f,
    val lowPercentile: Float = 5f,
    val highPercentile: Float = 99f,
    val noiseFloorMode: SpectrogramNoiseFloorMode = SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN,
    val noiseFloorPercentile: Float = 20f,
    val gateDb: Float = 6f,
    val displayRangeDb: Float = 30f,
    val gamma: Float = 1.5f,
    val maxInkArgb: Int = SpectrogramRenderProfile.MAX_INK_ARGB,
    val smoothingTimeRadius: Int = 1,
    val smoothingFrequencyRadius: Int = 1,
) {
    fun cacheSuffix(): String =
        listOf(
            displayRange.name.lowercase(),
            palette.name.lowercase(),
            "gain_${(gainDb * 10).toInt()}",
            "p${(lowPercentile * 10).toInt()}_${(highPercentile * 10).toInt()}",
            noiseFloorMode.name.lowercase(),
            "v1",
        ).joinToString("_")

    companion object {
        val BirdDefault = ReviewSpectrogramConfig()
    }
}
