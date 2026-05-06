package com.sound2inat.inference

/**
 * Mel-spectrogram parameters used for the Review screen visualisation only.
 *
 * BirdNET v2.4 (see docs/private/MODEL_SPIKE.md) consumes RAW audio of shape
 * [1, 144000]; the spectrogram produced here is NOT fed to the model. These
 * defaults are picked for a readable on-screen rendering at 48 kHz.
 */
data class MelParams(
    val sampleRate: Int = 48_000,
    val nFft: Int = 1024,
    val hop: Int = 512,
    val melBins: Int = 128,
    val fMin: Float = 0f,
    val fMax: Float = 10_000f, // default display range: 0–10 kHz (wildlife calls)
)
