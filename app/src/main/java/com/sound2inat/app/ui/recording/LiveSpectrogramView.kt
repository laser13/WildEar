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
 * Display window: dB values are remapped to this range before viridis lookup.
 * The offline renderer in Review uses dynamic per-clip min/max — we use an
 * EMA-adapted top + fixed dynamic range so colours stay stable across frames.
 */
private const val DB_DYNAMIC_RANGE = 60f      // floor = top - 60 dB
private const val DB_INITIAL_TOP = -10f       // starting top until EMA settles
private const val DB_TOP_EMA_ALPHA = 0.05f    // smoothing for adaptive top

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
    val dbTop = remember { floatArrayOf(DB_INITIAL_TOP) }   // single-elem holder, EMA-updated

    LaunchedEffect(audioBlocks) {
        audioBlocks.collect { block ->
            val columns = spectrogram.process(block)
            for (col in columns) {
                ring.append(logBinDown(col, BITMAP_HEIGHT_BINS))
                // Track the running peak to adapt the top of the colour window —
                // brings out content even when the recording is quiet.
                val peak = col.max()
                dbTop[0] = (1f - DB_TOP_EMA_ALPHA) * dbTop[0] + DB_TOP_EMA_ALPHA * peak
            }
            if (columns.isNotEmpty()) {
                drawRingIntoBitmap(bitmap, ring, dbTop[0])
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

private fun drawRingIntoBitmap(bm: Bitmap, ring: SpectrogramRingBuffer, dbTop: Float) {
    val w = bm.width; val h = bm.height
    val pixels = IntArray(w * h)
    val drawCols = ring.size.coerceAtMost(w)
    val xOffset = w - drawCols
    val dbBottom = dbTop - DB_DYNAMIC_RANGE
    val span = DB_DYNAMIC_RANGE
    for (x in 0 until drawCols) {
        val col = ring.column(x)
        for (y in 0 until h) {
            val db = col[h - 1 - y]   // flip so high freqs on top
            val t = ((db - dbBottom) / span).coerceIn(0f, 1f)
            pixels[y * w + (xOffset + x)] = Colormap.viridis(t)
        }
    }
    bm.setPixels(pixels, 0, w, 0, 0, w, h)
}
