package com.sound2inat.inference

class DetectionAggregator(
    private val minConfidence: Float = 0.10f,
    private val minWindows: Int = 1,
) {
    private val lock = Any()
    private val incremental = mutableListOf<WindowPrediction>()

    /**
     * Incremental version of [aggregate]: appends [pred] to an internal buffer
     * and returns the freshly aggregated list. Intended for live streaming —
     * the live recording screen calls this on every [WindowPrediction] emitted
     * by [LiveInferenceEngine] and renders the result as a list of LiveCards.
     *
     * Synchronised: callers may invoke from different threads (e.g. the
     * predictions Flow collector vs. a final-flush coroutine after stop).
     */
    fun addWindow(pred: WindowPrediction): List<AggregatedDetection> = synchronized(lock) {
        incremental.add(pred)
        aggregate(incremental.toList())
    }

    /** Re-aggregates the current incremental buffer without adding a new window. */
    fun snapshot(): List<AggregatedDetection> = synchronized(lock) {
        aggregate(incremental.toList())
    }

    /** Drops accumulated windows (e.g. between recordings). */
    fun reset() = synchronized(lock) { incremental.clear() }

    fun aggregate(preds: List<WindowPrediction>): List<AggregatedDetection> =
        preds
            .filter { it.confidence >= minConfidence }
            .filter { isLivingTaxon(it.taxonScientificName) }
            .groupBy { it.taxonScientificName }
            .map { (taxon, items) ->
                val groupedBySource   = items.filter { it.source.isNotEmpty() }.groupBy { it.source }
                val bySource          = groupedBySource.mapValues { (_, src) -> src.maxOf { it.confidence } }
                val windowsBySource   = groupedBySource.mapValues { (_, src) -> src.size }
                val firstSeenBySource = groupedBySource.mapValues { (_, src) -> src.minOf { it.startMs } }
                val lastSeenBySource  = groupedBySource.mapValues { (_, src) -> src.maxOf { it.endMs } }
                val ranges = items
                    .map { FragmentRange(it.startMs, it.endMs) }
                    .sortedBy { it.startMs }
                AggregatedDetection(
                    taxonScientificName = taxon,
                    taxonCommonName     = items.firstNotNullOfOrNull { it.taxonCommonName },
                    maxConfidence       = items.maxOf { it.confidence },
                    detectedWindows     = items.size,
                    firstSeenMs         = items.minOf { it.startMs },
                    lastSeenMs          = items.maxOf { it.endMs },
                    confidenceBySource  = bySource,
                    windowsBySource     = windowsBySource,
                    firstSeenBySource   = firstSeenBySource,
                    lastSeenBySource    = lastSeenBySource,
                    fragmentRanges      = ranges,
                    aggregatedConfidence = items.map { it.confidence }.average().toFloat(),
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
