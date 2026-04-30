package com.sound2inat.inference

class DetectionAggregator(
    private val minConfidence: Float = 0.10f,
    private val minWindows: Int = 1,
) {
    fun aggregate(preds: List<WindowPrediction>): List<AggregatedDetection> =
        preds
            .filter { it.confidence >= minConfidence }
            .filter { isLivingTaxon(it.taxonScientificName) }
            .groupBy { it.taxonScientificName }
            .map { (taxon, items) ->
                val bySource = items
                    .filter { it.source.isNotEmpty() }
                    .groupBy { it.source }
                    .mapValues { (_, src) -> src.maxOf { it.confidence } }
                AggregatedDetection(
                    taxonScientificName = taxon,
                    taxonCommonName = items.firstNotNullOfOrNull { it.taxonCommonName },
                    maxConfidence = items.maxOf { it.confidence },
                    detectedWindows = items.size,
                    firstSeenMs = items.minOf { it.startMs },
                    lastSeenMs = items.maxOf { it.endMs },
                    confidenceBySource = bySource,
                )
            }
            .filter { it.detectedWindows >= minWindows }
            .sortedByDescending { it.maxConfidence }

    /**
     * BirdNET v2.4 ships `noise` classes alongside species — labels like
     * `Fireworks_Fireworks`, `Engine_Engine`, `Siren_Siren`, `Power tools_…`.
     * They're useful inside the model (they mop up non-bird audio so a real
     * species doesn't get the prediction), but downstream they masquerade
     * as detections, end up in the Review list, and (worst) iNaturalist
     * happily resolves "Fireworks" to a *plant* — `Solidago rugosa` —
     * because `/taxa?q=...` is full-text across all kingdoms.
     *
     * Filter rule: a real Linnaean binomial is always "Genus species" —
     * two words separated by whitespace. Noise labels are single tokens.
     * This is a sharp, false-positive-safe heuristic.
     */
    private fun isLivingTaxon(scientificName: String): Boolean =
        scientificName.trim().contains(' ')
}
