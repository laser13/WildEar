package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.DisplayRangeSpec
import com.sound2inat.app.ui.spectrogram.LiveDefaults
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile

/**
 * Review-screen spectrogram visual configuration.
 *
 * [displayRange], [palette] and [gainDb] are nullable; null means "follow the
 * live-recording defaults" resolved through [LiveDefaults]. Use
 * [effectiveRangeSpec], [effectivePalette] and [effectiveGainDb] when you need
 * a concrete value for rendering.
 */
data class ReviewSpectrogramConfig(
    val displayRange: SpectrogramDisplayRange? = null,
    val palette: SpectrogramPalette? = null,
    val gainDb: Float? = null,
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
    /** Concrete frequency window: enum-derived when [displayRange] is set, otherwise [LiveDefaults.displayRange]. */
    val effectiveRangeSpec: DisplayRangeSpec
        get() = displayRange?.let { DisplayRangeSpec(it.fMinHz, it.fMaxHz) } ?: LiveDefaults.displayRange()

    /** Concrete palette: [palette] when set, otherwise [LiveDefaults.palette]. */
    val effectivePalette: SpectrogramPalette
        get() = palette ?: LiveDefaults.palette

    /** Concrete visual gain in dB: [gainDb] when set, otherwise [LiveDefaults.gainDb]. */
    val effectiveGainDb: Float
        get() = gainDb ?: LiveDefaults.gainDb

    fun cacheSuffix(): String =
        listOf(
            "dr_${displayRange?.name?.lowercase() ?: LIVE_TOKEN}",
            "pl_${palette?.name?.lowercase() ?: LIVE_TOKEN}",
            "gain_${gainDb?.let { (it * 10).toInt().toString() } ?: LIVE_TOKEN}",
            "lp_${(lowPercentile * 10).toInt()}",
            "hp_${(highPercentile * 10).toInt()}",
            "nf_${noiseFloorMode.name.lowercase()}_${(noiseFloorPercentile * 10).toInt()}",
            "gate_${(gateDb * 10).toInt()}",
            "range_${(displayRangeDb * 10).toInt()}",
            "gamma_${(gamma * 100).toInt()}",
            "ink_${maxInkArgb.toUInt().toString(16)}",
            "st_$smoothingTimeRadius",
            "sf_$smoothingFrequencyRadius",
            "v3",
        ).joinToString("_")

    fun displayPlaneCacheSuffix(): String =
        listOf(
            "dr_${displayRange?.name?.lowercase() ?: LIVE_TOKEN}",
            "gain_${gainDb?.let { (it * 10).toInt().toString() } ?: LIVE_TOKEN}",
            "lp_${(lowPercentile * 10).toInt()}",
            "hp_${(highPercentile * 10).toInt()}",
            "nf_${noiseFloorMode.name.lowercase()}_${(noiseFloorPercentile * 10).toInt()}",
            "gate_${(gateDb * 10).toInt()}",
            "range_${(displayRangeDb * 10).toInt()}",
            "gamma_${(gamma * 100).toInt()}",
            "st_$smoothingTimeRadius",
            "sf_$smoothingFrequencyRadius",
            "v2",
        ).joinToString("_")

    companion object {
        private const val LIVE_TOKEN = "live"
        val BirdDefault = ReviewSpectrogramConfig()
    }
}
