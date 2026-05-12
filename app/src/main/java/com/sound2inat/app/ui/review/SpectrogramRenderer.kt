package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.SpectrogramColorMap
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramPostProcessor
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile
import com.sound2inat.app.ui.spectrogram.SpectrogramVisualPipeline
import com.sound2inat.audio.Spectrogram
import com.sound2inat.inference.MelParams

/**
 * Pure-JVM spectrogram pixel renderer. Produces a `[height][width]` matrix
 * of ARGB ints from raw mono audio samples by:
 *
 *   1. Computing the same STFT columns used by the live spectrogram.
 *   2. Applying [SpectrogramNoiseFloorMode] background subtraction (default: per-column median).
 *   3. Applying display-only gate/range/gamma mapping so low-level texture
 *      stays pale instead of being over-normalized.
 *   4. Downsampling along the time axis to at most [targetWidth] columns.
 *   5. Flipping the Y axis so high frequencies render at the top.
 *   6. Mapping each cell through the configured [SpectrogramPalette] (default: [SpectrogramPalette.INK]).
 *
 * The result has `height == DISPLAY_HEIGHT_BINS` and `width <= targetWidth`.
 *
 * Android-specific PNG persistence lives in [SpectrogramBitmap] so this
 * class stays unit-testable on the JVM.
 */
class SpectrogramRenderer(
    private val melParams: MelParams = MelParams(),
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
) {
    private val inkLut: IntArray by lazy { SpectrogramColorMap.ink(backgroundArgb, maxInkArgb) }

    /**
     * Render [samples] (mono float, normalised to roughly `[-1, 1]`).
     * Returns an empty array if the audio is shorter than one FFT window.
     */
    fun render(samples: FloatArray): Array<IntArray> {
        if (samples.size < FFT_SIZE) return emptyArray()
        val columns = Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = melParams.sampleRate)
            .process(samples)
        val frames = columns.size
        if (frames == 0) return emptyArray()
        val binned = buildBinnedMatrix(columns, frames)
        val normalized = normalizeForDisplay(binned, DISPLAY_HEIGHT_BINS, frames)

        val width = minOf(targetWidth, frames)
        val out = Array(DISPLAY_HEIGHT_BINS) { IntArray(width) }
        for (x in 0 until width) {
            // Map output column [x] → input frame range [start, end).
            val start = (x.toLong() * frames / width).toInt()
            val end = ((x + 1).toLong() * frames / width).toInt().coerceAtMost(frames)
            val span = (end - start).coerceAtLeast(1)
            for (m in 0 until DISPLAY_HEIGHT_BINS) {
                var acc = 0f
                for (f in start until start + span) {
                    acc += normalized[m][f]
                }
                val norm = (acc / span).coerceIn(0f, 1f)
                // Flip Y: highest frequency bin (top of image) gets the largest m.
                val y = DISPLAY_HEIGHT_BINS - 1 - m
                out[y][x] = when (palette) {
                    SpectrogramPalette.INK -> SpectrogramColorMap.map(norm, inkLut)
                    SpectrogramPalette.VIRIDIS -> Colormap.viridis(norm)
                }
            }
        }
        return out
    }

    private fun buildBinnedMatrix(columns: List<FloatArray>, frames: Int): Array<FloatArray> {
        val matrix = Array(DISPLAY_HEIGHT_BINS) { FloatArray(frames) }
        val sortBuf = FloatArray(FFT_SIZE / 2 + 1)
        columns.forEachIndexed { frame, col ->
            if (noiseFloorMode == SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN) {
                SpectrogramVisualPipeline.whitenColumnInPlace(col, sortBuf)
            }
            val binned = SpectrogramVisualPipeline.logBinDown(
                src = col,
                outBins = DISPLAY_HEIGHT_BINS,
                sampleRateHz = melParams.sampleRate,
                minFrequencyHz = displayRange.fMinHz,
                maxFrequencyHz = displayRange.fMaxHz,
            )
            for (row in 0 until DISPLAY_HEIGHT_BINS) matrix[row][frame] = binned[row]
        }
        return when (noiseFloorMode) {
            SpectrogramNoiseFloorMode.NONE,
            SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN -> matrix
            SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN,
            SpectrogramNoiseFloorMode.PER_FREQUENCY_PERCENTILE ->
                SpectrogramPostProcessor.applyNoiseFloor(matrix, noiseFloorMode, noiseFloorPercentile)
        }
    }

    private fun normalizeForDisplay(
        mel: Array<FloatArray>,
        melBins: Int,
        frames: Int,
    ): Array<FloatArray> {
        val normalized = if (noiseFloorMode == SpectrogramNoiseFloorMode.NONE) {
            val allValues = FloatArray(melBins * frames)
            var idx = 0
            for (m in 0 until melBins) {
                val row = mel[m]
                for (f in 0 until frames) allValues[idx++] = row[f]
            }
            val clamped = applyTopDbClamp(allValues, displayRangeDb)
            val flat = percentileNormalize(clamped)
            Array(melBins) { m ->
                FloatArray(frames) { f -> flat[m * frames + f] }
            }
        } else {
            Array(melBins) { m ->
                SpectrogramPostProcessor.applyDisplayCurve(
                    values = mel[m],
                    gateDb = gateDb,
                    displayRangeDb = displayRangeDb,
                    gamma = gamma,
                )
            }
        }
        return SpectrogramPostProcessor.smoothNormalized(
            normalized,
            timeRadius = smoothingTimeRadius,
            frequencyRadius = smoothingFrequencyRadius,
        )
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 2048
        const val DISPLAY_HEIGHT_BINS = 256

        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 512

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
