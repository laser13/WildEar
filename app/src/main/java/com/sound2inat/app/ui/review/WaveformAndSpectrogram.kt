package com.sound2inat.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sound2inat.inference.WindowPrediction
import kotlinx.coroutines.flow.StateFlow

/**
 * Cached mel-spectrogram view with the play cursor and (when supplied)
 * per-detection rectangles. The waveform strip was removed — at typical
 * Compose dp sizes individual peaks were unreadable and the strip just
 * stole vertical space from the spectrogram.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun WaveformAndSpectrogram(
    @Suppress("UNUSED_PARAMETER") peaks: FloatArray?,
    spectrogramPath: String?,
    durationMs: Long,
    positionFlow: StateFlow<Long>,
    windowPreds: List<WindowPrediction>,
    species: List<SpeciesRow>,
    highlight: Long?,
    onWindowTap: (WindowPrediction) -> Unit,
    onSeek: (Long) -> Unit,
) {
    // Position read is local to this subtree so the 50 ms tick from
    // MediaPlayer does not trigger recomposition of the entire Review screen.
    val positionMs by positionFlow.collectAsState()
    val cursor: Float = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPECTROGRAM_HEIGHT.dp)
                // Tap-to-seek anywhere on the spectrogram. Detection-overlay
                // taps are handled inside DetectionOverlays and don't reach
                // here, so this fires for the bare-spectrogram regions only.
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0L && size.width > 0) {
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek((frac * durationMs).toLong())
                        }
                    }
                },
        ) {
            val bitmap: ImageBitmap? = remember(spectrogramPath) {
                spectrogramPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Mel spectrogram",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DetectionOverlays(
                windowPreds = windowPreds,
                species = species,
                highlight = highlight,
                durationMs = durationMs,
                onTap = onWindowTap,
            )
            val cursorColor = MaterialTheme.colorScheme.error
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = cursor * size.width
                drawLine(
                    color = cursorColor,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = CURSOR_STROKE_PX,
                )
            }
        }
    }
}

private const val SPECTROGRAM_HEIGHT = 220
private const val CURSOR_STROKE_PX = 2f
