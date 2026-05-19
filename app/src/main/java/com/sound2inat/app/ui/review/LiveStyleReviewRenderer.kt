package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.SpectrogramColorMap
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.app.ui.spectrogram.SpectrogramRenderProfile
import com.sound2inat.app.ui.spectrogram.SpectrogramVisualPipeline
import com.sound2inat.audio.Spectrogram

/**
 * Batch-mode renderer that mirrors [com.sound2inat.app.ui.recording.LiveSpectrogramView]:
 * STFT → per-column whiten → log-binned to a fixed 256-row mel-ish axis (500 Hz – 10 kHz)
 * → `displayValue` curve with the LiveBird profile, modulated by the user's contrast
 * delta → palette LUT. The result is a single full-recording display plane plus a
 * preview bitmap, no per-preset branching, no PER_COLUMN_MEDIAN/percentile gymnastics.
 *
 * Stateless / re-entrant — call sites can render concurrently.
 */
internal object LiveStyleReviewRenderer {

    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = 512
    private const val HEIGHT_BINS = 256
    private const val WHITE_ARGB = -1 // 0xFFFFFFFF — opaque white background, matches live
    private const val MIN_DISPLAY_DB = 6f

    fun render(
        samples: FloatArray,
        sampleRateHz: Int,
        palette: SpectrogramPalette,
        contrastDb: Float,
    ): RenderedSpectrogram {
        if (samples.isEmpty()) return RenderedSpectrogram(emptyDisplayPlane(), emptyPreview())
        val profile = SpectrogramRenderProfile.LiveBird
        val spectrogram = Spectrogram(FFT_SIZE, HOP_SIZE, sampleRateHz)
        val rawColumns = spectrogram.process(samples)
        if (rawColumns.isEmpty()) return RenderedSpectrogram(emptyDisplayPlane(), emptyPreview())

        val sortBuf = FloatArray(rawColumns.first().size)
        val width = rawColumns.size
        // [width][HEIGHT_BINS] — column = time, row = log-frequency bin (0 = lowest, HEIGHT_BINS-1 = highest)
        val binned = Array(width) { i ->
            val col = rawColumns[i]
            SpectrogramVisualPipeline.whitenColumnInPlace(col, sortBuf)
            SpectrogramVisualPipeline.logBinDown(
                src = col,
                outBins = HEIGHT_BINS,
                sampleRateHz = sampleRateHz,
                minFrequencyHz = profile.minFrequencyHz,
                maxFrequencyHz = profile.maxFrequencyHz,
            )
        }

        // Positive contrastDb shrinks the visible range (more contrast), negative widens it.
        val displayRangeDb = (profile.displayRangeDb - contrastDb).coerceAtLeast(MIN_DISPLAY_DB)
        val displayPlane = buildDisplayPlane(binned, width, profile.gateDb, displayRangeDb, profile.gamma)
        val preview = colorize(displayPlane, palette, profile.maxInkArgb)
        return RenderedSpectrogram(displayPlane, preview)
    }

    private fun buildDisplayPlane(
        binned: Array<FloatArray>,
        width: Int,
        gateDb: Float,
        displayRangeDb: Float,
        gamma: Float,
    ): ReviewSpectrogramDisplayPlane {
        val values = Array(HEIGHT_BINS) { y ->
            FloatArray(width) { x ->
                SpectrogramVisualPipeline.displayValue(
                    dbAboveFloor = binned[x][y],
                    gateDb = gateDb,
                    displayRangeDb = displayRangeDb,
                    gamma = gamma,
                )
            }
        }
        return ReviewSpectrogramDisplayPlane(width = width, height = HEIGHT_BINS, values = values)
    }

    /**
     * Re-colorizes an already-normalized [plane] with [palette]. Used by the
     * Compose layer when only the palette changes — no need to re-run STFT.
     */
    fun colorize(
        plane: ReviewSpectrogramDisplayPlane,
        palette: SpectrogramPalette,
        maxInkArgb: Int = SpectrogramRenderProfile.LiveBird.maxInkArgb,
    ): ReviewSpectrogramPreview {
        val lut = paletteLut(palette, maxInkArgb)
        val width = plane.width
        val height = plane.height
        if (width == 0 || height == 0) return emptyPreview()
        val argb = IntArray(width * height)
        // Bitmap orientation: row 0 = top of image = highest frequency.
        for (y in 0 until height) {
            val srcRow = height - 1 - y
            val row = plane.values[srcRow]
            val rowStart = y * width
            for (x in 0 until width) {
                argb[rowStart + x] = SpectrogramColorMap.map(row[x], lut)
            }
        }
        return ReviewSpectrogramPreview(width = width, height = height, argb = argb)
    }

    private fun paletteLut(palette: SpectrogramPalette, maxInkArgb: Int): IntArray = when (palette) {
        SpectrogramPalette.INK -> SpectrogramColorMap.ink(WHITE_ARGB, maxInkArgb)
        SpectrogramPalette.VIRIDIS -> SpectrogramColorMap.viridis()
        SpectrogramPalette.MAGMA -> SpectrogramColorMap.magma()
        SpectrogramPalette.GRAY -> SpectrogramColorMap.gray()
    }

    private fun emptyDisplayPlane() = ReviewSpectrogramDisplayPlane(0, 0, emptyArray())
    private fun emptyPreview() = ReviewSpectrogramPreview(0, 0, IntArray(0))
}

data class RenderedSpectrogram(
    val displayPlane: ReviewSpectrogramDisplayPlane,
    val preview: ReviewSpectrogramPreview,
)
