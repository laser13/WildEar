package com.sound2inat.inat

import com.sound2inat.audio.SpectrogramBitmap
import com.sound2inat.audio.SpectrogramPalette
import com.sound2inat.audio.SpectrogramPngRenderer
import com.sound2inat.audio.SpectrogramPreview
import com.sound2inat.audio.WavPcmReader
import java.io.File

/**
 * Renders a single PNG spectrogram from a mono-16 WAV file (the format
 * produced by [WavTrimmer.trimMono16]). The output PNG is sized by the
 * renderer's chosen preview dimensions and written to [destination].
 *
 * If [peakOffsetMs] is non-null, only the slice of samples in
 * `[peakOffsetMs.first .. peakOffsetMs.last]` (offsets in milliseconds
 * relative to the start of the cropped WAV, NOT absolute WAV-of-recording
 * timestamps) is fed to the renderer. Used to keep the spectrogram picture
 * compact for long clips while leaving the uploaded audio at full length.
 *
 * Returns the file if it was written successfully and is non-empty, else null.
 */
internal fun renderClipSpectrogramPng(
    clipWav: File,
    destination: File,
    peakOffsetMs: LongRange? = null,
    palette: SpectrogramPalette = SpectrogramPalette.INK,
    contrastDb: Float = 0f,
): File? {
    if (!clipWav.exists() || clipWav.length() <= 0L) return null
    val (shorts, sampleRateHz) = WavPcmReader.readMono16(clipWav)
    if (shorts.isEmpty()) return null
    val sliced: ShortArray = if (peakOffsetMs == null) shorts else sliceSamples(shorts, sampleRateHz, peakOffsetMs)
    if (sliced.isEmpty()) return null
    val samples = FloatArray(sliced.size) { i -> sliced[i] / Short.MAX_VALUE.toFloat() }
    val rendered = SpectrogramPngRenderer.render(
        samples = samples,
        sampleRateHz = sampleRateHz,
        palette = palette,
        contrastDb = contrastDb,
    )
    val preview: SpectrogramPreview = rendered.preview
    if (preview.width <= 0 || preview.height <= 0) return null
    val rows = Array(preview.height) { row ->
        IntArray(preview.width) { col ->
            preview.argb[row * preview.width + col]
        }
    }
    destination.parentFile?.mkdirs()
    SpectrogramBitmap.writePng(rows, destination)
    return destination.takeIf { it.exists() && it.length() > 0L }
}

private fun sliceSamples(shorts: ShortArray, sampleRateHz: Int, offsetMs: LongRange): ShortArray {
    val samplesPerMs = sampleRateHz / 1000.0
    val startIdx = (offsetMs.first * samplesPerMs).toInt().coerceIn(0, shorts.size)
    val endIdx = (offsetMs.last * samplesPerMs).toInt().coerceIn(startIdx, shorts.size)
    if (endIdx <= startIdx) return ShortArray(0)
    return shorts.copyOfRange(startIdx, endIdx)
}
