package com.sound2inat.app.ui.spectrogram

import kotlin.math.pow

enum class SpectrogramNoiseFloorMode {
    /** No noise floor removal — raw dB values pass through unchanged. */
    NONE,

    /**
     * Per-column (per-time-frame) median subtraction. Designed for live streaming
     * where the full recording is not yet available. See `whitenInPlace` in
     * `LiveSpectrogramView` for the streaming implementation.
     */
    PER_COLUMN_MEDIAN,

    /**
     * Per-frequency-bin median subtraction across all time frames. Removes
     * constant background (road noise, HVAC hum) that is stable over time,
     * making tonal bird calls stand out against a clean baseline. Requires
     * the full recording — not suitable for live streaming.
     */
    PER_FREQUENCY_MEDIAN,

    /**
     * Per-frequency-bin percentile subtraction across all time frames. A lower
     * percentile keeps the estimated background closer to the quiet floor than
     * median, which helps review spectrograms stay calmer after display gating.
     */
    PER_FREQUENCY_PERCENTILE,
}

/**
 * Offline post-processing for spectrogram mel matrices.
 * Operates on `Array<FloatArray>` shaped `[melBins][frames]`.
 * All functions return new arrays — inputs are never mutated.
 */
object SpectrogramPostProcessor {

    /**
     * Dispatches noise-floor removal based on [mode]. Returns the input unchanged
     * for [SpectrogramNoiseFloorMode.NONE] and [SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN]
     * (the latter is a live-streaming mode and is a no-op here).
     */
    fun applyNoiseFloor(
        mel: Array<FloatArray>,
        mode: SpectrogramNoiseFloorMode,
        noiseFloorPercentile: Float = 20f,
    ): Array<FloatArray> =
        when (mode) {
            SpectrogramNoiseFloorMode.NONE,
            SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN -> mel
            SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN -> subtractFrequencyMedian(mel)
            SpectrogramNoiseFloorMode.PER_FREQUENCY_PERCENTILE ->
                subtractFrequencyPercentile(mel, noiseFloorPercentile)
        }

    /**
     * For each frequency bin (row), subtracts the median dB value across all
     * time frames. This lifts each row's floor to 0 dB, revealing signal peaks
     * relative to the per-frequency noise floor.
     */
    fun subtractFrequencyMedian(mel: Array<FloatArray>): Array<FloatArray> {
        if (mel.isEmpty()) return mel
        return Array(mel.size) { m ->
            val row = mel[m]
            if (row.isEmpty()) return@Array row.copyOf()
            val sorted = row.copyOf()
            sorted.sort()
            val median = sorted[sorted.size / 2]
            FloatArray(row.size) { f -> row[f] - median }
        }
    }

    fun subtractFrequencyPercentile(mel: Array<FloatArray>, percentile: Float): Array<FloatArray> {
        if (mel.isEmpty()) return mel
        val boundedPercentile = percentile.coerceIn(0f, 100f) / 100f
        return Array(mel.size) { m ->
            val row = mel[m]
            if (row.isEmpty()) return@Array row.copyOf()
            val sorted = row.copyOf()
            sorted.sort()
            val index = ((sorted.size - 1) * boundedPercentile).toInt().coerceIn(0, sorted.size - 1)
            val floor = sorted[index]
            FloatArray(row.size) { f -> row[f] - floor }
        }
    }

    fun applyDisplayCurve(
        values: FloatArray,
        gateDb: Float,
        displayRangeDb: Float,
        gamma: Float,
    ): FloatArray {
        val safeRange = displayRangeDb.coerceAtLeast(1e-6f)
        val safeGamma = gamma.coerceAtLeast(1e-6f)
        return FloatArray(values.size) { i ->
            ((values[i] - gateDb).coerceIn(0f, safeRange) / safeRange).pow(safeGamma)
        }
    }

    fun smoothNormalized(
        matrix: Array<FloatArray>,
        timeRadius: Int,
        frequencyRadius: Int,
    ): Array<FloatArray> {
        if (matrix.isEmpty()) return matrix
        val rows = matrix.size
        val cols = matrix[0].size
        if (cols == 0 || (timeRadius <= 0 && frequencyRadius <= 0)) {
            return Array(rows) { r -> matrix[r].copyOf() }
        }
        val tRadius = timeRadius.coerceAtLeast(0)
        val fRadius = frequencyRadius.coerceAtLeast(0)
        return Array(rows) { r ->
            FloatArray(cols) { c ->
                var sum = 0f
                var count = 0
                val rowStart = (r - fRadius).coerceAtLeast(0)
                val rowEnd = (r + fRadius).coerceAtMost(rows - 1)
                val colStart = (c - tRadius).coerceAtLeast(0)
                val colEnd = (c + tRadius).coerceAtMost(cols - 1)
                for (rr in rowStart..rowEnd) {
                    for (cc in colStart..colEnd) {
                        sum += matrix[rr][cc]
                        count++
                    }
                }
                sum / count
            }
        }
    }
}
