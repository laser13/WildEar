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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.sound2inat.audio.Spectrogram
import com.sound2inat.audio.SpectrogramColorMap
import com.sound2inat.audio.SpectrogramRenderProfile
import com.sound2inat.audio.SpectrogramRingBuffer
import com.sound2inat.audio.SpectrogramVisualPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

private const val BITMAP_WIDTH_COLS = 940 // ~10 sec at 48k / hop 512
private const val BITMAP_HEIGHT_BINS = 256 // log-binned from 1025 to 256
private const val FFT_SIZE = 2048
private const val HOP_SIZE = 512

private val LIVE_PROFILE = SpectrogramRenderProfile.LiveBird

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
    val lut = remember(bgArgb) { SpectrogramColorMap.ink(bgArgb, LIVE_PROFILE.maxInkArgb) }

    val pixels = remember(bgArgb) { IntArray(BITMAP_WIDTH_COLS * BITMAP_HEIGHT_BINS) { bgArgb } }
    val ring = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
    val spectrogram = remember(sampleRateHz) {
        Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = sampleRateHz)
    }
    val sortBuf = remember { FloatArray(FFT_SIZE / 2 + 1) }
    val bitmapA = remember {
        Bitmap.createBitmap(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, Bitmap.Config.ARGB_8888)
    }
    val bitmapB = remember {
        Bitmap.createBitmap(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, Bitmap.Config.ARGB_8888)
    }
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(audioBlocks) {
        var backBitmap = bitmapB
        audioBlocks.collect { block ->
            val updated = withContext(Dispatchers.Default) {
                val columns = spectrogram.process(block)
                for (col in columns) {
                    SpectrogramVisualPipeline.whitenColumnInPlace(col, sortBuf)
                    ring.append(
                        SpectrogramVisualPipeline.logBinDown(
                            src = col,
                            outBins = BITMAP_HEIGHT_BINS,
                            sampleRateHz = sampleRateHz,
                            minFrequencyHz = LIVE_PROFILE.minFrequencyHz,
                            maxFrequencyHz = LIVE_PROFILE.maxFrequencyHz,
                        ),
                    )
                }
                if (columns.isNotEmpty()) {
                    fillPixels(pixels, ring, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, lut, bgArgb)
                    backBitmap.setPixels(pixels, 0, BITMAP_WIDTH_COLS, 0, 0, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS)
                    true
                } else {
                    false
                }
            }
            if (updated) {
                imageBitmap = backBitmap.asImageBitmap()
                backBitmap = if (backBitmap === bitmapA) bitmapB else bitmapA
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
    if (xOffset > 0) {
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until xOffset) out[rowStart + x] = bgArgb
        }
    }
    // Outer loop over rows (y) so consecutive writes go to out[y*w + ...],
    // which is sequential in memory and cache-friendly.
    for (y in 0 until h) {
        val ringY = h - 1 - y
        for (x in 0 until drawCols) {
            val px = xOffset + x
            val db = SpectrogramVisualPipeline.smoothedRingValue(
                ring = ring,
                x = x,
                y = ringY,
                timeRadius = LIVE_PROFILE.smoothingTimeRadius,
                frequencyRadius = LIVE_PROFILE.smoothingFrequencyRadius,
            )
            val ink = SpectrogramVisualPipeline.displayValue(
                dbAboveFloor = db,
                gateDb = LIVE_PROFILE.gateDb,
                displayRangeDb = LIVE_PROFILE.displayRangeDb,
                gamma = LIVE_PROFILE.gamma,
            )
            out[y * w + px] = SpectrogramColorMap.map(ink, lut)
        }
    }
}
