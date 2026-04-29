package com.sound2inat.app.ui.review

import com.sound2inat.inference.MelParams
import com.sound2inat.inference.MelSpectrogram

/**
 * Pure-JVM spectrogram pixel renderer. Produces a `[height][width]` matrix
 * of ARGB ints from raw mono audio samples by:
 *
 *   1. Computing the mel-spectrogram via [MelSpectrogram] (Task 4).
 *   2. Normalising dB values across the matrix to `[0, 1]`.
 *   3. Downsampling along the time axis to at most [targetWidth] columns.
 *   4. Flipping the Y axis so high frequencies render at the top.
 *   5. Mapping each cell through the [Colormap.viridis] LUT.
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

        // Find dB min / max across all (bin, frame) pairs for normalisation.
        var minDb = Float.POSITIVE_INFINITY
        var maxDb = Float.NEGATIVE_INFINITY
        for (m in 0 until melBins) {
            val row = mel[m]
            for (f in 0 until frames) {
                val v = row[f]
                if (v < minDb) minDb = v
                if (v > maxDb) maxDb = v
            }
        }
        val range = (maxDb - minDb).coerceAtLeast(MIN_RANGE)

        val width = minOf(targetWidth, frames)
        val out = Array(melBins) { IntArray(width) }
        for (x in 0 until width) {
            // Map output column [x] → input frame range [start, end).
            val start = (x.toLong() * frames / width).toInt()
            val end = ((x + 1).toLong() * frames / width).toInt().coerceAtMost(frames)
            val span = (end - start).coerceAtLeast(1)
            for (m in 0 until melBins) {
                var acc = 0f
                val row = mel[m]
                for (f in start until start + span) acc += row[f]
                val avg = acc / span
                val norm = ((avg - minDb) / range).coerceIn(0f, 1f)
                // Flip Y: highest mel bin (top of image) gets the largest m.
                val y = melBins - 1 - m
                out[y][x] = Colormap.viridis(norm)
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 2048
        private const val MIN_RANGE = 1e-6f
    }
}
