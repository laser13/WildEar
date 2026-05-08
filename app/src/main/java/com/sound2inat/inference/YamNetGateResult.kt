package com.sound2inat.inference

enum class GateRecommendation { PASS, DOWNRANK }

data class YamNetGateResult(
    val biologicalScore: Float,
    val backgroundScore: Float,
    val recommendation: GateRecommendation,
)
