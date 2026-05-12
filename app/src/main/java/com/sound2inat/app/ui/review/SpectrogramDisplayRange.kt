package com.sound2inat.app.ui.review

enum class SpectrogramDisplayRange(val fMinHz: Int, val fMaxHz: Int, val label: String) {
    WILDLIFE(80, 10_000, "80 Hz–10 kHz"),
    BIRD_FOCUSED(600, 10_000, "600 Hz–10 kHz"),
    OWL_LOW_VOICE(80, 6_000, "80 Hz–6 kHz"),
    INSECT_AMPHIBIAN(1_000, 16_000, "1–16 kHz"),
    FULL(0, 24_000, "Full"),
}
