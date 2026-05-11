package com.sound2inat.inference

class DetectionAggregator(
    private val minConfidence: Float = 0.10f,
    private val minWindows: Int = 1,
) {
    private val lock = Any()

    // Per-taxon running accumulators for the streaming path (addWindow / snapshot).
    private val accumulators = LinkedHashMap<String, Accumulator>()

    /**
     * O(log n) streaming update: appends [pred] to the per-taxon accumulator
     * and returns the current sorted snapshot. n = windows accumulated so far
     * for this taxon (log factor is the insertion into the sorted fragment-range list).
     * The prior O(n) cost came from calling [aggregate] on the full window list
     * for every incoming prediction — up to O(n²) over a full recording.
     */
    fun addWindow(pred: WindowPrediction): List<AggregatedDetection> = synchronized(lock) {
        if (pred.confidence >= minConfidence && isLivingTaxon(pred.taxonScientificName)) {
            accumulators.getOrPut(pred.taxonScientificName) { Accumulator(pred.taxonScientificName) }
                .add(pred)
        }
        buildSnapshot()
    }

    /** Returns the current aggregated state without adding a new window. */
    fun snapshot(): List<AggregatedDetection> = synchronized(lock) { buildSnapshot() }

    /** Drops accumulated windows (e.g. between recordings). */
    fun reset() = synchronized(lock) { accumulators.clear() }

    private fun buildSnapshot(): List<AggregatedDetection> =
        accumulators.values
            .filter { it.windowCount >= minWindows }
            .map { it.toDetection() }
            .sortedByDescending { it.maxConfidence }

    /**
     * Stateless batch aggregation — unchanged from original. Used by the offline
     * inference path ([InferenceRunner]) and existing tests.
     */
    fun aggregate(preds: List<WindowPrediction>): List<AggregatedDetection> =
        preds
            .filter { it.confidence >= minConfidence }
            .filter { isLivingTaxon(it.taxonScientificName) }
            .groupBy { it.taxonScientificName }
            .map { (taxon, items) ->
                val groupedBySource = items.filter { it.source.isNotEmpty() }.groupBy { it.source }
                val bySource = groupedBySource.mapValues { (_, src) -> src.maxOf { it.confidence } }
                val windowsBySource = groupedBySource.mapValues { (_, src) -> src.size }
                val firstSeenBySource = groupedBySource.mapValues { (_, src) -> src.minOf { it.startMs } }
                val lastSeenBySource = groupedBySource.mapValues { (_, src) -> src.maxOf { it.endMs } }
                val ranges = items
                    .map { FragmentRange(it.startMs, it.endMs) }
                    .sortedBy { it.startMs }
                AggregatedDetection(
                    taxonScientificName = taxon,
                    taxonCommonName = items.firstNotNullOfOrNull { it.taxonCommonName },
                    maxConfidence = items.maxOf { it.confidence },
                    detectedWindows = items.size,
                    firstSeenMs = items.minOf { it.startMs },
                    lastSeenMs = items.maxOf { it.endMs },
                    confidenceBySource = bySource,
                    windowsBySource = windowsBySource,
                    firstSeenBySource = firstSeenBySource,
                    lastSeenBySource = lastSeenBySource,
                    fragmentRanges = ranges,
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

    /**
     * Per-taxon running state for the incremental streaming path.
     * Mirrors the computation in [aggregate] exactly so that [buildSnapshot]
     * and [aggregate] produce identical [AggregatedDetection] values.
     *
     * [confSum] uses Double to match [List.average]'s internal accumulation,
     * ensuring [AggregatedDetection.aggregatedConfidence] is bit-for-bit equal
     * between the incremental and batch paths.
     */
    private class Accumulator(val scientificName: String) {
        var commonName: String? = null
        var maxConf: Float = 0f
        var windowCount: Int = 0
        var firstSeen: Long = Long.MAX_VALUE
        var lastSeen: Long = Long.MIN_VALUE
        val confBySource = mutableMapOf<String, Float>()
        val winsBySource = mutableMapOf<String, Int>()
        val firstBySource = mutableMapOf<String, Long>()
        val lastBySource = mutableMapOf<String, Long>()
        val ranges = mutableListOf<FragmentRange>()
        var confSum: Double = 0.0

        fun add(pred: WindowPrediction) {
            if (commonName == null && pred.taxonCommonName != null) commonName = pred.taxonCommonName
            maxConf = maxOf(maxConf, pred.confidence)
            windowCount++
            firstSeen = minOf(firstSeen, pred.startMs)
            lastSeen = maxOf(lastSeen, pred.endMs)
            confSum += pred.confidence
            val src = pred.source
            if (src.isNotEmpty()) {
                confBySource[src] = maxOf(confBySource.getOrDefault(src, 0f), pred.confidence)
                winsBySource[src] = (winsBySource.getOrDefault(src, 0)) + 1
                firstBySource[src] = minOf(firstBySource.getOrDefault(src, Long.MAX_VALUE), pred.startMs)
                lastBySource[src] = maxOf(lastBySource.getOrDefault(src, 0L), pred.endMs)
            }
            // Maintain ranges sorted by startMs via insertion sort.
            // Windows typically arrive in order, so the while loop executes 0 iterations
            // on average and only scans backwards on out-of-order arrivals.
            val range = FragmentRange(pred.startMs, pred.endMs)
            var i = ranges.size
            while (i > 0 && ranges[i - 1].startMs > range.startMs) i--
            ranges.add(i, range)
        }

        fun toDetection() = AggregatedDetection(
            taxonScientificName = scientificName,
            taxonCommonName = commonName,
            maxConfidence = maxConf,
            detectedWindows = windowCount,
            firstSeenMs = firstSeen,
            lastSeenMs = lastSeen,
            confidenceBySource = confBySource.toMap(),
            windowsBySource = winsBySource.toMap(),
            firstSeenBySource = firstBySource.toMap(),
            lastSeenBySource = lastBySource.toMap(),
            fragmentRanges = ranges.toList(),
            aggregatedConfidence = (confSum / windowCount).toFloat(),
        )
    }
}
