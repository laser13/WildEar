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
            "dr_${displayRange.name.lowercase()}",
            "pl_${palette.name.lowercase()}",
            "gain_${(gainDb * 10).toInt()}",
            "lp_${(lowPercentile * 10).toInt()}",
            "hp_${(highPercentile * 10).toInt()}",
            "nf_${noiseFloorMode.name.lowercase()}_${(noiseFloorPercentile * 10).toInt()}",
            "gate_${(gateDb * 10).toInt()}",
            "range_${(displayRangeDb * 10).toInt()}",
            "gamma_${(gamma * 100).toInt()}",
            "ink_${maxInkArgb.toUInt().toString(16)}",
            "st_$smoothingTimeRadius",
            "sf_$smoothingFrequencyRadius",
            "v2",
        ).joinToString("_")

    fun displayPlaneCacheSuffix(): String =
        listOf(
            "dr_${displayRange.name.lowercase()}",
            "gain_${(gainDb * 10).toInt()}",
            "lp_${(lowPercentile * 10).toInt()}",
            "hp_${(highPercentile * 10).toInt()}",
            "nf_${noiseFloorMode.name.lowercase()}_${(noiseFloorPercentile * 10).toInt()}",
            "gate_${(gateDb * 10).toInt()}",
            "range_${(displayRangeDb * 10).toInt()}",
            "gamma_${(gamma * 100).toInt()}",
            "st_$smoothingTimeRadius",
            "sf_$smoothingFrequencyRadius",
            "v1",
        ).joinToString("_")

    companion object {
        val BirdDefault = ReviewSpectrogramConfig()
    }
}
