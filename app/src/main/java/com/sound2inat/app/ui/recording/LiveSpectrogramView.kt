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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.sound2inat.audio.Spectrogram
import com.sound2inat.audio.SpectrogramRingBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

private const val BITMAP_WIDTH_COLS = 940 // ~10 sec at 48k / hop 512
private const val BITMAP_HEIGHT_BINS = 256 // log-binned from 1025 to 256
private const val FFT_SIZE = 2048
private const val HOP_SIZE = 512

/** Default display ceiling: show 0–10 kHz (wildlife calls). */
private const val DISPLAY_MAX_HZ = 10_000

/**
 * Display window in **dB above the per-column noise floor** (set by
 * [whitenInPlace]). Independent of recording gain / mic sensitivity:
 * 0 = at noise floor → white, [DB_DISPLAY_MAX] dB above floor → black.
 * Needed because UNPROCESSED audio source produces much lower absolute
 * dBFS than gain-controlled sources, so a fixed-dBFS window can't hit
 * both quiet whistles and clean background simultaneously.
 */
private const val DB_DISPLAY_MIN = 0f
private const val DB_DISPLAY_MAX = 35f

private const val LUT_SIZE = 256

/**
 * Builds a Merlin-style ink LUT that blends from [bgArgb] (silence) to near-black
 * (loud peak). Parameterised so the spectrogram background matches the app theme.
 */
private fun buildInkLut(bgArgb: Int): IntArray {
    val bgR = (bgArgb shr 16) and 0xFF
    val bgG = (bgArgb shr 8) and 0xFF
    val bgB = bgArgb and 0xFF
    return IntArray(LUT_SIZE) { i ->
        val t = i / (LUT_SIZE - 1f)
        val tGamma = sqrt(t)
        val r = (bgR * (1f - tGamma)).toInt().coerceIn(0, 255)
        val g = (bgG * (1f - tGamma)).toInt().coerceIn(0, 255)
        val b = (bgB * (1f - tGamma)).toInt().coerceIn(0, 255)
        Color.rgb(r, g, b)
    }
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
    backgroundColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
) {
    val bgArgb = backgroundColor.toArgb()
    val lut = remember(bgArgb) { buildInkLut(bgArgb) }

    val pixels = remember(bgArgb) { IntArray(BITMAP_WIDTH_COLS * BITMAP_HEIGHT_BINS) { bgArgb } }
    val ring = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
    val spectrogram = remember(sampleRateHz) {
        Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = sampleRateHz)
    }
    val sortBuf = remember { FloatArray(FFT_SIZE / 2 + 1) }
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(audioBlocks) {
        audioBlocks.collect { block ->
            val snapshot: IntArray? = withContext(Dispatchers.Default) {
                val columns = spectrogram.process(block)
                for (col in columns) {
                    whitenInPlace(col, sortBuf)
                    ring.append(logBinDown(col, BITMAP_HEIGHT_BINS, sampleRateHz))
                }
                if (columns.isNotEmpty()) {
                    fillPixels(pixels, ring, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, lut, bgArgb)
                    pixels.copyOf() // hand off a completed snapshot to the main thread
                } else {
                    null
                }
            }
            if (snapshot != null) {
                val bmp = Bitmap.createBitmap(
                    BITMAP_WIDTH_COLS,
                    BITMAP_HEIGHT_BINS,
                    Bitmap.Config.ARGB_8888,
                )
                bmp.setPixels(snapshot, 0, BITMAP_WIDTH_COLS, 0, 0, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS)
                imageBitmap = bmp.asImageBitmap()
            }
        }
    }

    imageBitmap?.let { img ->
        Image(
            bitmap = img,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

/**
 * Maps the linear FFT magnitude column (size = fftSize/2 + 1, e.g. 1025) to
 * [outBins] log-spaced rows covering 0–[DISPLAY_MAX_HZ] Hz. Low frequencies
 * (where bird calls live) get more vertical space than high frequencies.
 *
 * Uses **MAX-pooling** over each row's source-bin range — sampling a single
 * bin (nearest-neighbour) silently dropped narrow tones at high frequencies,
 * because adjacent log rows skip over 5–10 source bins each. Whistles
 * landing on a skipped bin would disappear; speech survived only because
 * its energy spreads across many bins.
 *
 * The frequency ceiling is determined by [sampleRateHz] and [DISPLAY_MAX_HZ]:
 * bins above the [DISPLAY_MAX_HZ] cut-off are excluded so the display focuses
 * on wildlife call frequencies (default 0–10 kHz).
 */
private fun logBinDown(src: FloatArray, outBins: Int, sampleRateHz: Int): FloatArray {
    val out = FloatArray(outBins)
    val nyquistBins = src.size // fftSize/2 + 1
    // FFT bin index corresponding to DISPLAY_MAX_HZ.
    val displayMaxBin = (DISPLAY_MAX_HZ.toLong() * (nyquistBins - 1) * 2 / sampleRateHz)
        .toInt()
        .coerceIn(1, nyquistBins - 1)
    val logMin = 0.0 // covers DC at the bottom row
    val logMax = ln(displayMaxBin.toDouble())
    val outScale = (outBins - 1).toDouble()
    for (j in 0 until outBins) {
        val fracLo = ((j - 0.5).coerceAtLeast(0.0)) / outScale
        val fracHi = ((j + 0.5).coerceAtMost(outScale)) / outScale
        val lo = exp(logMin + fracLo * (logMax - logMin)).toInt().coerceIn(0, displayMaxBin)
        val hi = exp(logMin + fracHi * (logMax - logMin)).toInt().coerceIn(lo, displayMaxBin)
        var maxVal = src[lo]
        for (k in (lo + 1)..hi) {
            if (src[k] > maxVal) maxVal = src[k]
        }
        out[j] = maxVal
    }
    return out
}

/**
 * Subtract the per-column median dB ("noise floor") from every bin in place.
 * After this, [col] holds dB **relative to the noise floor**: 0 = at floor,
 * positive = above floor (signal). Lets the display work the same regardless
 * of input gain. [sortBuf] is a scratch buffer of size `col.size` reused
 * across calls to avoid allocations.
 *
 * Median is robust because in typical recordings most bins sit at the noise
 * floor with only a few peaks; the 50th percentile reliably picks the floor.
 */
private fun whitenInPlace(col: FloatArray, sortBuf: FloatArray) {
    System.arraycopy(col, 0, sortBuf, 0, col.size)
    sortBuf.sort()
    val median = sortBuf[sortBuf.size / 2]
    for (i in col.indices) col[i] = col[i] - median
}

private fun fillPixels(
    out: IntArray,
    ring: SpectrogramRingBuffer,
    w: Int,
    h: Int,
    lut: IntArray,
    bgArgb: Int,
) {
    val drawCols = ring.size.coerceAtMost(w)
    val xOffset = w - drawCols
    val span = DB_DISPLAY_MAX - DB_DISPLAY_MIN
    val lutMax = LUT_SIZE - 1
    if (xOffset > 0) {
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until xOffset) out[rowStart + x] = bgArgb
        }
    }
    for (x in 0 until drawCols) {
        val col = ring.column(x)
        val px = xOffset + x
        for (y in 0 until h) {
            val db = col[h - 1 - y]
            val linear = ((db - DB_DISPLAY_MIN) / span).coerceIn(0f, 1f)
            val lutIdx = (linear * lutMax).toInt().coerceIn(0, lutMax)
            out[y * w + px] = lut[lutIdx]
        }
    }
}
