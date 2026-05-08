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
    // 4096 keeps FFT bin width (~12 Hz) narrower than the narrowest mel
    // triangle in the visible band, so every mel filter always contains at
    // least one FFT bin centre. With smaller nFft (e.g. 1024 → 47 Hz/bin)
    // sub-1 kHz mel bins occasionally land entirely between FFT bins and
    // render as empty horizontal stripes through the signal.
    val nFft: Int = 4096,
    val hop: Int = 512,
    val melBins: Int = 128,
    val fMin: Float = 0f,
    val fMax: Float = 10_000f, // default display range: 0–10 kHz (wildlife calls)
)
