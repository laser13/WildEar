package com.sound2inat.inference

enum class GateRecommendation { PASS, DOWNRANK }

data class YamNetGateResult(
    val biologicalScore: Float,
    val backgroundScore: Float,
    val recommendation: GateRecommendation,
)

/** Threshold above which a high-confidence prediction overrides a DOWNRANK gate decision. */
const val GATE_HIGH_CONFIDENCE_OVERRIDE = 0.7f

/**
 * Returns true when this gate result should suppress [predictions] from reaching
 * the aggregator — i.e. the recommendation is DOWNRANK and no prediction clears
 * [GATE_HIGH_CONFIDENCE_OVERRIDE].
 *
 * Callers pass `this` as the gate result (null = fail-open = never suppress).
 */
fun YamNetGateResult?.suppressesPredictions(predictions: List<WindowPrediction>): Boolean =
    this?.recommendation == GateRecommendation.DOWNRANK &&
        predictions.none { it.confidence >= GATE_HIGH_CONFIDENCE_OVERRIDE }
