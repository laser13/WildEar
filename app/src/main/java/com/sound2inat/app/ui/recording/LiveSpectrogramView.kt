package com.sound2inat.app.ui.recording

import android.graphics.Bitmap
import android.graphics.Color
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
import com.sound2inat.audio.Spectrogram
import com.sound2inat.audio.SpectrogramRingBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

private const val BITMAP_WIDTH_COLS = 940     // ~10 sec at 48k / hop 512
private const val BITMAP_HEIGHT_BINS = 256    // log-binned from 1025 to 256
private const val FFT_SIZE = 2048
private const val HOP_SIZE = 512

/**
 * Fixed dB window for the colour lookup. We can't do offline-style dynamic
 * min/max rescale (it'd flicker the whole image), so we apply gamma to lift
 * quiet content out of the floor. Per-pixel mapping never depends on
 * neighbours, so no flicker.
 */
private const val DB_DISPLAY_MIN = -75f
private const val DB_DISPLAY_MAX = -5f

/**
 * Pre-computed Merlin-style ink LUT: white background, increasing darkness as
 * energy rises. Index 0 = pure white (silence), index LUT_SIZE-1 = near black
 * (loud peak). Quantised to 256 entries — per-pixel work is one int lookup.
 */
private const val LUT_SIZE = 256
private val inkLut: IntArray = IntArray(LUT_SIZE) { i ->
    val t = i / (LUT_SIZE - 1f)
    // Apply gamma 0.5 here so the lookup itself encodes the curve.
    val tGamma = sqrt(t)
    // White → black; alpha stays opaque.
    val grey = ((1f - tGamma) * 255).toInt().coerceIn(0, 255)
    Color.rgb(grey, grey, grey)
}

/**
 * Renders a [SharedFlow] of float audio blocks as a scrolling sonogram with
 * a white background and dark strokes for sound (Merlin Sound ID style).
 * STFT (FFT 2048, hop 512) → log-binned to 256 frequency rows. Bitmap shows
 * ~10 seconds at 48 kHz; older columns scroll left.
 *
 * Heavy work (STFT + bitmap fill) runs on [Dispatchers.Default] — the UI
 * thread only does setPixels (memcpy) + recomposition.
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
    val pixels = remember { IntArray(BITMAP_WIDTH_COLS * BITMAP_HEIGHT_BINS) { Color.WHITE } }
    val ring = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
    val spectrogram = remember(sampleRateHz) {
        Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = sampleRateHz)
    }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(audioBlocks) {
        audioBlocks.collect { block ->
            val drew = withContext(Dispatchers.Default) {
                val columns = spectrogram.process(block)
                for (col in columns) {
                    ring.append(logBinDown(col, BITMAP_HEIGHT_BINS))
                }
                if (columns.isNotEmpty()) {
                    fillPixels(pixels, ring, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS)
                    true
                } else {
                    false
                }
            }
            if (drew) {
                bitmap.setPixels(pixels, 0, BITMAP_WIDTH_COLS, 0, 0, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS)
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

private fun fillPixels(out: IntArray, ring: SpectrogramRingBuffer, w: Int, h: Int) {
    val drawCols = ring.size.coerceAtMost(w)
    val xOffset = w - drawCols
    val span = DB_DISPLAY_MAX - DB_DISPLAY_MIN
    val lutMax = LUT_SIZE - 1
    val white = Color.WHITE
    // Clear leading uncovered area to white once per frame.
    if (xOffset > 0) {
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until xOffset) out[rowStart + x] = white
        }
    }
    for (x in 0 until drawCols) {
        val col = ring.column(x)
        val px = xOffset + x
        for (y in 0 until h) {
            val db = col[h - 1 - y]
            val linear = ((db - DB_DISPLAY_MIN) / span).coerceIn(0f, 1f)
            val lutIdx = (linear * lutMax).toInt().coerceIn(0, lutMax)
            out[y * w + px] = inkLut[lutIdx]
        }
    }
}
