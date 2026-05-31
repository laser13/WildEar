package com.sound2inat.audio

/**
 * Batch-mode renderer that mirrors [com.sound2inat.app.ui.recording.LiveSpectrogramView]:
 * STFT → per-column whiten → log-binned to a fixed 256-row mel-ish axis (500 Hz – 10 kHz)
 * → `displayValue` curve with the LiveBird profile, modulated by the user's contrast
 * delta → palette LUT. The result is a single full-recording display plane plus a
 * preview bitmap, no per-preset branching, no PER_COLUMN_MEDIAN/percentile gymnastics.
 *
 * Stateless / re-entrant — call sites can render concurrently.
 */
object SpectrogramPngRenderer {

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

    /**
     * Streaming variant of [render]: feeds [Spectrogram] block-by-block instead of
     * holding the whole decoded signal in memory. [blockSource] is invoked once and
     * must push every PCM block (floats in ≈[-1,1]) into the provided callback, in
     * order, exactly as [render] would have received them concatenated. Only the
     * per-column binned plane (width × HEIGHT_BINS) is accumulated — never the full
     * sample buffer nor the raw STFT columns.
     *
     * Equivalent to `render(allSamplesConcatenated, ...)` because [Spectrogram]
     * keeps STFT state across [Spectrogram.process] calls.
     */
    fun renderStreaming(
        sampleRateHz: Int,
        palette: SpectrogramPalette,
        contrastDb: Float,
        blockSource: (onBlock: (FloatArray) -> Unit) -> Unit,
    ): RenderedSpectrogram {
        val profile = SpectrogramRenderProfile.LiveBird
        val spectrogram = Spectrogram(FFT_SIZE, HOP_SIZE, sampleRateHz)
        val binnedColumns = ArrayList<FloatArray>()
        var sortBuf: FloatArray? = null

        blockSource { block ->
            for (col in spectrogram.process(block)) {
                val buf = sortBuf ?: FloatArray(col.size).also { sortBuf = it }
                SpectrogramVisualPipeline.whitenColumnInPlace(col, buf)
                binnedColumns += SpectrogramVisualPipeline.logBinDown(
                    src = col,
                    outBins = HEIGHT_BINS,
                    sampleRateHz = sampleRateHz,
                    minFrequencyHz = profile.minFrequencyHz,
                    maxFrequencyHz = profile.maxFrequencyHz,
                )
            }
        }

        if (binnedColumns.isEmpty()) return RenderedSpectrogram(emptyDisplayPlane(), emptyPreview())

        val width = binnedColumns.size
        val binned = binnedColumns.toTypedArray()
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
    ): SpectrogramDisplayPlane {
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
        return SpectrogramDisplayPlane(width = width, height = HEIGHT_BINS, values = values)
    }

    /**
     * Re-colorizes an already-normalized [plane] with [palette]. Used by the
     * Compose layer when only the palette changes — no need to re-run STFT.
     */
    fun colorize(
        plane: SpectrogramDisplayPlane,
        palette: SpectrogramPalette,
        maxInkArgb: Int = SpectrogramRenderProfile.LiveBird.maxInkArgb,
    ): SpectrogramPreview {
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
        return SpectrogramPreview(width = width, height = height, argb = argb)
    }

    private fun paletteLut(palette: SpectrogramPalette, maxInkArgb: Int): IntArray = when (palette) {
        SpectrogramPalette.INK -> SpectrogramColorMap.ink(WHITE_ARGB, maxInkArgb)
        SpectrogramPalette.VIRIDIS -> SpectrogramColorMap.viridis()
        SpectrogramPalette.MAGMA -> SpectrogramColorMap.magma()
        SpectrogramPalette.GRAY -> SpectrogramColorMap.gray()
    }

    private fun emptyDisplayPlane() = SpectrogramDisplayPlane(0, 0, emptyArray())
    private fun emptyPreview() = SpectrogramPreview(0, 0, IntArray(0))
}

data class RenderedSpectrogram(
    val displayPlane: SpectrogramDisplayPlane,
    val preview: SpectrogramPreview,
)
