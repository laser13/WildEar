package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.SpectrogramColorMap
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramPostProcessor
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile
import com.sound2inat.app.ui.spectrogram.SpectrogramVisualPipeline

/**
 * Pure-JVM spectrogram pixel renderer. Produces a `[height][width]` matrix
 * of ARGB ints from a reusable [ReviewSpectrogramMatrix] by:
 *
 *   1. Copying the cached matrix so rendering never mutates shared data.
 *   2. Applying visual gain.
 *   3. Applying noise-floor handling and display-only gate/range/gamma mapping.
 *   4. Smoothing the normalized values.
 *   5. Downsampling along the time axis to at most [targetWidth] columns.
 *   6. Flipping the Y axis so high frequencies render at the top.
 *   7. Mapping each cell through the configured [SpectrogramPalette] (default: [SpectrogramPalette.INK]).
 *
 * Android-specific PNG persistence lives in [SpectrogramBitmap] so this
 * class stays unit-testable on the JVM.
 */
class SpectrogramRenderer(
    private val targetWidth: Int = DEFAULT_TARGET_WIDTH,
    private val palette: SpectrogramPalette = SpectrogramPalette.INK,
    private val backgroundArgb: Int = WHITE_ARGB,
    private val displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.BIRD_FOCUSED,
    private val noiseFloorMode: SpectrogramNoiseFloorMode = SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN,
    private val noiseFloorPercentile: Float = 20f,
    private val gateDb: Float = 6f,
    private val displayRangeDb: Float = 30f,
    private val gamma: Float = 1.5f,
    private val maxInkArgb: Int = SpectrogramRenderProfile.MAX_INK_ARGB,
    private val smoothingTimeRadius: Int = 1,
    private val smoothingFrequencyRadius: Int = 1,
    private val config: ReviewSpectrogramConfig? = null,
) {
    private val renderConfig: ReviewSpectrogramConfig by lazy {
        config ?: ReviewSpectrogramConfig(
            displayRange = displayRange,
            palette = palette,
            gainDb = 0f,
            lowPercentile = 5f,
            highPercentile = 95f,
            noiseFloorMode = noiseFloorMode,
            noiseFloorPercentile = noiseFloorPercentile,
            gateDb = gateDb,
            displayRangeDb = displayRangeDb,
            gamma = gamma,
            maxInkArgb = maxInkArgb,
            smoothingTimeRadius = smoothingTimeRadius,
            smoothingFrequencyRadius = smoothingFrequencyRadius,
        )
    }
    private val inkLut: IntArray by lazy { SpectrogramColorMap.ink(backgroundArgb, renderConfig.maxInkArgb) }
    private val colorLut: IntArray by lazy {
        when (renderConfig.palette) {
            SpectrogramPalette.INK -> inkLut
            SpectrogramPalette.VIRIDIS -> SpectrogramColorMap.viridis()
            SpectrogramPalette.MAGMA -> SpectrogramColorMap.magma()
            SpectrogramPalette.GRAY -> SpectrogramColorMap.gray()
        }
    }

    /**
     * Render [matrix] into ARGB pixels.
     */
    fun render(matrix: ReviewSpectrogramMatrix): Array<IntArray> {
        if (matrix.frames == 0 || matrix.values.isEmpty()) return emptyArray()

        val working = copyMatrix(matrix)
        if (renderConfig.noiseFloorMode == SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN) {
            applyPerColumnMedianInPlace(working)
        }
        addGainInPlace(working)
        val noiseAdjusted = when (renderConfig.noiseFloorMode) {
            SpectrogramNoiseFloorMode.NONE,
            SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN -> working
            SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN,
            SpectrogramNoiseFloorMode.PER_FREQUENCY_PERCENTILE ->
                SpectrogramPostProcessor.applyNoiseFloor(
                    working,
                    renderConfig.noiseFloorMode,
                    renderConfig.noiseFloorPercentile,
                )
        }
        val normalized = normalizeForDisplay(noiseAdjusted)
        val smoothed = SpectrogramPostProcessor.smoothNormalized(
            normalized,
            timeRadius = renderConfig.smoothingTimeRadius,
            frequencyRadius = renderConfig.smoothingFrequencyRadius,
        )
        return downsampleAndColor(smoothed)
    }

    private fun copyMatrix(matrix: ReviewSpectrogramMatrix): Array<FloatArray> =
        Array(matrix.values.size) { row -> matrix.values[row].copyOf() }

    private fun addGainInPlace(matrix: Array<FloatArray>) {
        if (renderConfig.gainDb == 0f) return
        for (row in matrix.indices) {
            val data = matrix[row]
            for (i in data.indices) {
                data[i] += renderConfig.gainDb
            }
        }
    }

    private fun applyPerColumnMedianInPlace(matrix: Array<FloatArray>) {
        if (matrix.isEmpty()) return
        val rows = matrix.size
        val frames = matrix[0].size
        if (frames == 0) return
        val sortBuf = FloatArray(rows)
        val column = FloatArray(rows)
        for (frame in 0 until frames) {
            for (row in 0 until rows) {
                column[row] = matrix[row][frame]
            }
            SpectrogramVisualPipeline.whitenColumnInPlace(column, sortBuf)
            for (row in 0 until rows) {
                matrix[row][frame] = column[row]
            }
        }
    }

    private fun normalizeForDisplay(mel: Array<FloatArray>): Array<FloatArray> {
        if (mel.isEmpty()) return mel
        val rows = mel.size
        val frames = mel[0].size
        if (frames == 0) return Array(rows) { row -> mel[row].copyOf() }
        val normalized = if (renderConfig.noiseFloorMode == SpectrogramNoiseFloorMode.NONE) {
            val allValues = FloatArray(rows * frames)
            var idx = 0
            for (row in 0 until rows) {
                for (frame in 0 until frames) allValues[idx++] = mel[row][frame]
            }
            val clamped = applyTopDbClamp(allValues, renderConfig.displayRangeDb)
            val flat = percentileNormalize(
                clamped,
                lowPercentile = renderConfig.lowPercentile,
                highPercentile = renderConfig.highPercentile,
            )
            Array(rows) { row ->
                FloatArray(frames) { f -> flat[row * frames + f] }
            }
        } else {
            Array(rows) { row ->
                SpectrogramPostProcessor.applyDisplayCurve(
                    values = mel[row],
                    gateDb = renderConfig.gateDb,
                    displayRangeDb = renderConfig.displayRangeDb,
                    gamma = renderConfig.gamma,
                )
            }
        }
        return normalized
    }

    private fun downsampleAndColor(normalized: Array<FloatArray>): Array<IntArray> {
        if (normalized.isEmpty()) return emptyArray()
        val height = normalized.size
        val frames = normalized[0].size
        if (frames == 0) return emptyArray()

        val width = minOf(targetWidth, frames)
        val out = Array(height) { IntArray(width) }
        for (x in 0 until width) {
            val start = (x.toLong() * frames / width).toInt()
            val end = ((x + 1).toLong() * frames / width).toInt().coerceAtMost(frames)
            val span = (end - start).coerceAtLeast(1)
            for (m in 0 until height) {
                var acc = 0f
                for (f in start until start + span) {
                    acc += normalized[m][f]
                }
                val norm = (acc / span).coerceIn(0f, 1f)
                val y = height - 1 - m
                out[y][x] = SpectrogramColorMap.map(norm, colorLut)
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 2048
        const val DISPLAY_HEIGHT_BINS = 256

        /** Maximum dynamic range in decibels. Values below (max - TOP_DB) are lifted to the floor. */
        const val TOP_DB = 75f

        private const val WHITE_ARGB = -1 // 0xFFFFFFFF: fully opaque white

        /**
         * Clamps [values] so the dynamic range is at most [topDb] dB.
         * Any value below (max − topDb) is raised to that floor.
         */
        fun applyTopDbClamp(values: FloatArray, topDb: Float = TOP_DB): FloatArray {
            if (values.isEmpty()) return values
            val maxDb = values.max()
            val floor = maxDb - topDb
            return FloatArray(values.size) { values[it].coerceAtLeast(floor) }
        }

        /**
         * Normalises [values] to [0, 1] using the 5th and 95th percentiles as
         * floor and ceiling respectively. This prevents a single very loud event
         * from compressing the dynamic range of quieter calls.
         */
        fun percentileNormalize(
            values: FloatArray,
            lowPercentile: Float = 5f,
            highPercentile: Float = 95f,
        ): FloatArray {
            if (values.isEmpty()) return values
            val sorted = values.toMutableList().also { it.sort() }
            val low = sorted[
                ((sorted.size - 1) * (lowPercentile.coerceIn(0f, 100f) / 100f))
                    .toInt()
                    .coerceIn(0, sorted.size - 1)
            ]
            val high = sorted[
                ((sorted.size - 1) * (highPercentile.coerceIn(0f, 100f) / 100f))
                    .toInt()
                    .coerceIn(0, sorted.size - 1)
            ]
            val range = (high - low).coerceAtLeast(1e-6f)
            return FloatArray(values.size) { ((values[it] - low) / range).coerceIn(0f, 1f) }
        }
    }
}
