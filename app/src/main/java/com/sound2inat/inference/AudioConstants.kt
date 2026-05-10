package com.sound2inat.inference

object AudioConstants {
    const val BIRDNET_SAMPLE_RATE_HZ = 48_000
    const val PERCH_SAMPLE_RATE_HZ = 32_000

    /** BirdNET v2.4 window: 3 seconds at 48 kHz. */
    const val BIRDNET_WINDOW_SAMPLES = 144_000

    /** Perch v2 window: 5 seconds at 32 kHz. */
    const val PERCH_WINDOW_SAMPLES = 160_000
}
