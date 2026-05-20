// TODO(architecture): LiveStyleReviewRenderer lives in com.sound2inat.app.ui.review
// (an internal object). Calling it from the com.sound2inat.inat package compiles only
// because this is a single Gradle module, but it inverts the documented package
// dependency (inat should not depend on ui.review). Extract the DSP core into
// com.sound2inat.audio in a follow-up.
package com.sound2inat.inat

import com.sound2inat.app.ui.review.LiveStyleReviewRenderer
import com.sound2inat.app.ui.review.ReviewSpectrogramPreview
import com.sound2inat.app.ui.review.SpectrogramBitmap
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inference.WavReader
import java.io.File

/**
 * Renders a single PNG spectrogram from a mono-16 WAV file (the format
 * produced by [WavTrimmer.trimMono16]). The output PNG is sized by the
 * renderer's chosen preview dimensions and written to [destination].
 *
 * Returns the file if it was written successfully and is non-empty, else null.
 */
internal fun renderClipSpectrogramPng(
    clipWav: File,
    destination: File,
    palette: SpectrogramPalette = SpectrogramPalette.INK,
    contrastDb: Float = 0f,
): File? {
    if (!clipWav.exists() || clipWav.length() <= 0L) return null
    val (shorts, sampleRateHz) = WavReader.readMono16(clipWav)
    if (shorts.isEmpty()) return null
    val samples = FloatArray(shorts.size) { i -> shorts[i] / Short.MAX_VALUE.toFloat() }
    val rendered = LiveStyleReviewRenderer.render(
        samples = samples,
        sampleRateHz = sampleRateHz,
        palette = palette,
        contrastDb = contrastDb,
    )
    val preview: ReviewSpectrogramPreview = rendered.preview
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
