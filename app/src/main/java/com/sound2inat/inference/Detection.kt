package com.sound2inat.inference

data class WindowPrediction(
    val startMs: Long,
    val endMs: Long,
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val confidence: Float,
    /**
     * Model that produced this prediction (e.g. `birdnet_v2_4`, `perch_v2`).
     * Empty string when the producer is unknown; downstream aggregator falls
     * back to "combined" in that case.
     */
    val source: String = "",
)

data class AggregatedDetection(
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /**
     * Per-model best confidence for this taxon. Empty map when only a single
     * unidentified source was used (e.g. legacy code paths).
     */
    val confidenceBySource: Map<String, Float> = emptyMap(),
)
