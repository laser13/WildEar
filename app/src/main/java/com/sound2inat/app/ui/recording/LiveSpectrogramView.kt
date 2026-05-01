package com.sound2inat.app.ui.recording

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.sound2inat.app.ui.review.Colormap
import com.sound2inat.audio.Spectrogram
import com.sound2inat.audio.SpectrogramRingBuffer
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.exp
import kotlin.math.ln

private const val BITMAP_WIDTH_COLS = 940     // ~10 sec at 48k / hop 512
private const val BITMAP_HEIGHT_BINS = 256    // log-binned from 1025 to 256
private const val FFT_SIZE = 2048
private const val HOP_SIZE = 512

/**
 * Fixed dB window for the viridis lookup. The offline Review renderer uses
 * dynamic per-clip min/max which gives bright teal/yellow contrast — but for
 * live we can't normalise across an unknown future, and an EMA-tracked top
 * makes the whole image flicker whenever a loud sound arrives.
 *
 * Instead we keep a fixed dB window and apply a gamma < 1 to the normalised
 * value before the viridis lookup. Gamma lifts quiet content (typical mic
 * background ~ -65 dB) out of the dark-purple end into the blue-teal mid
 * range, while loud peaks still saturate the bright yellow end. Per-pixel
 * mapping never depends on neighbours, so no flicker.
 */
private const val DB_DISPLAY_MIN = -75f
private const val DB_DISPLAY_MAX = -5f
private const val GAMMA = 0.5f                // sqrt — pushes low values up

/**
 * Renders a [SharedFlow] of float audio blocks as a scrolling dB heatmap.
 * STFT (FFT 2048, hop 512) → log-binned to 256 frequency rows → ARGB heatmap.
 * The bitmap has BITMAP_WIDTH_COLS columns, sized to display ~10 seconds of
 * audio at 48 kHz. Older columns scroll left and are dropped.
 *
 * Heavy STFT work happens in the coroutine launched by LaunchedEffect — UI
 * thread only does setPixels + recomposition.
 */
@Suppress("FunctionNaming")
@Composable
fun LiveSpectrogramView(
    audioBlocks: SharedFlow<FloatArray>,
    sampleRateHz: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember {
        Bitmap.createBitmap(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, Bitmap.Config.ARGB_8888)
    }
    val ring = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
    val spectrogram = remember(sampleRateHz) {
        Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = sampleRateHz)
    }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(audioBlocks) {
        audioBlocks.collect { block ->
            val columns = spectrogram.process(block)
            for (col in columns) {
                ring.append(logBinDown(col, BITMAP_HEIGHT_BINS))
            }
            if (columns.isNotEmpty()) {
                drawRingIntoBitmap(bitmap, ring)
                revision++
            }
        }
    }

    @Suppress("UNUSED_EXPRESSION") revision  // force recomposition on bitmap mutation
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * Maps the linear FFT magnitude column (size = fftSize/2 + 1, e.g. 1025) to
 * [outBins] log-spaced rows. Low frequencies (where bird calls live) get more
 * vertical space than high frequencies.
 */
private fun logBinDown(src: FloatArray, outBins: Int): FloatArray {
    val out = FloatArray(outBins)
    val srcMaxIdx = src.size - 1
    val logMin = 0.0  // covers DC at the bottom row
    val logMax = ln(srcMaxIdx.toDouble())
    for (j in 0 until outBins) {
        val frac = j.toDouble() / (outBins - 1)
        val srcIdxLog = logMin + frac * (logMax - logMin)
        val srcIdx = exp(srcIdxLog).toInt().coerceIn(0, srcMaxIdx)
        out[j] = src[srcIdx]
    }
    return out
}

private fun drawRingIntoBitmap(bm: Bitmap, ring: SpectrogramRingBuffer) {
    val w = bm.width; val h = bm.height
    val pixels = IntArray(w * h)
    val drawCols = ring.size.coerceAtMost(w)
    val xOffset = w - drawCols
    val span = DB_DISPLAY_MAX - DB_DISPLAY_MIN
    for (x in 0 until drawCols) {
        val col = ring.column(x)
        for (y in 0 until h) {
            val db = col[h - 1 - y]   // flip so high freqs on top
            val linear = ((db - DB_DISPLAY_MIN) / span).coerceIn(0f, 1f)
            val t = kotlin.math.sqrt(linear)   // gamma 0.5 — see GAMMA constant
            pixels[y * w + (xOffset + x)] = Colormap.viridis(t)
        }
    }
    bm.setPixels(pixels, 0, w, 0, 0, w, h)
}
