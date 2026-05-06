package com.sound2inat.app.ui.review

enum class SpectrogramDisplayRange(val fMinHz: Int, val fMaxHz: Int, val label: String) {
    WILDLIFE(0, 10_000, "0–10 kHz"),
    BIRD_FOCUSED(1_000, 10_000, "1–10 kHz"),
    INSECT_AMPHIBIAN(0, 16_000, "0–16 kHz"),
    FULL(0, 24_000, "Full"),
}
