package com.sound2inat.app.ui.spectrogram

object LiveDefaults {

    fun displayRange(): DisplayRangeSpec {
        val profile = SpectrogramRenderProfile.LiveBird
        return DisplayRangeSpec(profile.minFrequencyHz, profile.maxFrequencyHz)
    }

    val palette: SpectrogramPalette = SpectrogramPalette.INK

    const val gainDb: Float = 0f
}
