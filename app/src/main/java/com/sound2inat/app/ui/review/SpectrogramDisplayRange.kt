package com.sound2inat.app.ui.review

enum class SpectrogramDisplayRange(
    val fMinHz: Int,
    val fMaxHz: Int,
    val displayName: String,
    val rangeLabel: String,
) {
    BIRDNET_BIRD(1_000, 12_000, "BirdNET", "1–12 kHz"),
    WILDLIFE(80, 10_000, "Wildlife", "80 Hz – 10 kHz"),
    BIRD_FOCUSED(600, 10_000, "Songbirds", "600 Hz – 10 kHz"),
    OWL_LOW_VOICE(80, 6_000, "Owls & low calls", "80 Hz – 6 kHz"),
    INSECT_AMPHIBIAN(1_000, 16_000, "Insects & frogs", "1–16 kHz"),
    FULL(0, 24_000, "Full", "0 – 24 kHz"),
}
