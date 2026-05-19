package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.DisplayRangeSpec
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile

/**
 * Review-screen spectrogram visual configuration.
 *
 * Both [palette] and [gainDb] are nullable; null means "follow the live-recording
 * defaults" (grey INK palette, 0 dB contrast). The display range is fixed to the
 * live profile range (500 Hz – 10 kHz). Use [effectiveRangeSpec], [effectivePalette]
 * and [effectiveGainDb] when you need a concrete value for rendering.
 */
data class ReviewSpectrogramConfig(
    val palette: SpectrogramPalette? = null,
    val gainDb: Float? = null,
) {
    val effectiveRangeSpec: DisplayRangeSpec
        get() {
            val live = SpectrogramRenderProfile.LiveBird
            return DisplayRangeSpec(live.minFrequencyHz, live.maxFrequencyHz)
        }

    val effectivePalette: SpectrogramPalette
        get() = palette ?: SpectrogramPalette.INK

    val effectiveGainDb: Float
        get() = gainDb ?: 0f

    companion object {
        val BirdDefault = ReviewSpectrogramConfig()
    }
}
