package com.sound2inat.app.ui.review

data class ReviewAudioProcessingConfig(
    val preset: Preset,
    val highPassHz: Int? = null,
    val gainDb: Float = 0f,
    val normalizePeak: Boolean = false,
) {
    enum class Preset { ORIGINAL, BIRD_CLEAN, BIRD_HIGH_CLARITY, BOOST_QUIET, CUSTOM }

    val requiresProcessing: Boolean
        get() = highPassHz != null || gainDb != 0f || normalizePeak

    fun withHighPass(highPassHz: Int?): ReviewAudioProcessingConfig =
        copy(preset = Preset.CUSTOM, highPassHz = highPassHz)

    fun withGain(gainDb: Float): ReviewAudioProcessingConfig =
        copy(preset = Preset.CUSTOM, gainDb = gainDb)

    fun withNormalize(normalizePeak: Boolean): ReviewAudioProcessingConfig =
        copy(preset = Preset.CUSTOM, normalizePeak = normalizePeak)

    fun cacheSuffix(): String =
        listOf(
            preset.name.lowercase(),
            "hp_${highPassHz ?: 0}",
            "gain_${(gainDb * 10).toInt()}",
            "norm_${normalizePeak}",
            "v1",
        ).joinToString("_")

    companion object {
        val Original = ReviewAudioProcessingConfig(Preset.ORIGINAL)
        val BirdClean = ReviewAudioProcessingConfig(
            preset = Preset.BIRD_CLEAN,
            highPassHz = 600,
            gainDb = 0f,
            normalizePeak = true,
        )
        val BirdHighClarity = ReviewAudioProcessingConfig(
            preset = Preset.BIRD_HIGH_CLARITY,
            highPassHz = 1_000,
            gainDb = 0f,
            normalizePeak = true,
        )
        val BoostQuiet = ReviewAudioProcessingConfig(
            preset = Preset.BOOST_QUIET,
            highPassHz = 600,
            gainDb = 6f,
            normalizePeak = true,
        )
        val Custom = ReviewAudioProcessingConfig(Preset.CUSTOM)
    }
}

fun reviewHighPassOptions(): List<Int?> = listOf(null, 300, 600, 1_000, 1_600)

fun reviewGainOptions(): List<Float> = listOf(-12f, -6f, 0f, 6f, 12f)
