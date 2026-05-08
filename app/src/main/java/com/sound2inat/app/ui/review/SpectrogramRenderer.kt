package com.sound2inat.app.ui.review

import com.sound2inat.inference.MelParams
import com.sound2inat.inference.MelSpectrogram

/**
 * Pure-JVM spectrogram pixel renderer. Produces a `[height][width]` matrix
 * of ARGB ints from raw mono audio samples by:
 *
 *   1. Computing the mel-spectrogram via [MelSpectrogram] (Task 4).
 *   2. Applying a top-dB clamp so the dynamic range is at most [TOP_DB] dB.
 *   3. Normalising dB values per rendered window using percentile bounds
 *      (p5 → 0, p95 → 1) to prevent loud low-frequency rumble from flattening
 *      quiet calls.
 *   4. Downsampling along the time axis to at most [targetWidth] columns.
 *   5. Flipping the Y axis so high frequencies render at the top.
 *   6. Mapping each cell through the [Colormap.viridis] LUT.
 *
 * The result has `height == melParams.melBins` and `width <= targetWidth`.
 *
 * Android-specific PNG persistence lives in [SpectrogramBitmap] so this
 * class stays unit-testable on the JVM.
 */
class SpectrogramRenderer(
    private val melParams: MelParams = MelParams(),
    private val targetWidth: Int = DEFAULT_TARGET_WIDTH,
) {

    /**
     * Render [samples] (mono float, normalised to roughly `[-1, 1]`).
     * Returns an empty array if the audio is shorter than one FFT window.
     */
    fun render(samples: FloatArray): Array<IntArray> {
        if (samples.size < melParams.nFft) return emptyArray()
        val mel = MelSpectrogram(melParams).compute(samples)
        val melBins = mel.size
        val frames = mel[0].size
        if (frames == 0) return emptyArray()

        // Flatten mel matrix for global normalization operations.
        val allValues = FloatArray(melBins * frames)
        var idx = 0
        for (m in 0 until melBins) {
            val row = mel[m]
            for (f in 0 until frames) allValues[idx++] = row[f]
        }

        // Apply top-dB clamp: restrict dynamic range to at most TOP_DB decibels.
        val topDbClampedValues = applyTopDbClamp(allValues, TOP_DB)

        // Percentile normalization: p5 → 0, p95 → 1.
        val normalizedValues = percentileNormalize(topDbClampedValues)

        val width = minOf(targetWidth, frames)
        val out = Array(melBins) { IntArray(width) }
        for (x in 0 until width) {
            // Map output column [x] → input frame range [start, end).
            val start = (x.toLong() * frames / width).toInt()
            val end = ((x + 1).toLong() * frames / width).toInt().coerceAtMost(frames)
            val span = (end - start).coerceAtLeast(1)
            for (m in 0 until melBins) {
                var acc = 0f
                for (f in start until start + span) {
                    acc += normalizedValues[m * frames + f]
                }
                val norm = (acc / span).coerceIn(0f, 1f)
                // Flip Y: highest mel bin (top of image) gets the largest m.
                val y = melBins - 1 - m
                out[y][x] = Colormap.viridis(norm)
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 2048

        /** Maximum dynamic range in decibels. Values below (max - TOP_DB) are lifted to the floor. */
        const val TOP_DB = 75f

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
        fun percentileNormalize(values: FloatArray): FloatArray {
            if (values.isEmpty()) return values
            val sorted = values.toMutableList().also { it.sort() }
            val p5 = sorted[(sorted.size * 0.05f).toInt().coerceIn(0, sorted.size - 1)]
            val p95 = sorted[(sorted.size * 0.95f).toInt().coerceIn(0, sorted.size - 1)]
            val range = (p95 - p5).coerceAtLeast(1e-6f)
            return FloatArray(values.size) { ((values[it] - p5) / range).coerceIn(0f, 1f) }
        }
    }
}
