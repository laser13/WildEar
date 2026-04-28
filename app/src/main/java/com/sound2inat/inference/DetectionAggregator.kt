package com.sound2inat.inference

class DetectionAggregator(private val minConfidence: Float = 0.10f) {
    fun aggregate(preds: List<WindowPrediction>): List<AggregatedDetection> =
        preds.asSequence()
            .filter { it.confidence >= minConfidence }
            .groupBy { it.taxonScientificName }
            .map { (taxon, items) ->
                AggregatedDetection(
                    taxonScientificName = taxon,
                    taxonCommonName = items.firstNotNullOfOrNull { it.taxonCommonName },
                    maxConfidence = items.maxOf { it.confidence },
                    detectedWindows = items.size,
                    firstSeenMs = items.minOf { it.startMs },
                    lastSeenMs = items.maxOf { it.endMs },
                )
            }
            .sortedByDescending { it.maxConfidence }
}
