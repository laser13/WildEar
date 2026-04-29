package com.sound2inat.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sound2inat.inference.WindowPrediction
import kotlinx.coroutines.flow.StateFlow

/**
 * Stacked waveform + cached mel-spectrogram view with the play cursor and
 * (when supplied) per-detection rectangles. The spectrogram `Box` shares its
 * coordinate system with [DetectionOverlays] so taps on overlay rectangles
 * map back to `WindowPrediction`s without extra DPI math.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun WaveformAndSpectrogram(
    peaks: FloatArray?,
    spectrogramPath: String?,
    durationMs: Long,
    positionFlow: StateFlow<Long>,
    windowPreds: List<WindowPrediction>,
    species: List<SpeciesRow>,
    highlight: Long?,
    onWindowTap: (WindowPrediction) -> Unit,
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
        // Waveform — 96 dp tall.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WAVEFORM_HEIGHT.dp),
        ) {
            val waveColor = MaterialTheme.colorScheme.primary
            val cursorColor = MaterialTheme.colorScheme.error
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val p = peaks
                if (p != null && p.size >= 2) {
                    val cols = p.size / 2
                    val colW = w / cols
                    for (i in 0 until cols) {
                        val lo = p[2 * i]
                        val hi = p[2 * i + 1]
                        val y0 = mid - hi * mid
                        val y1 = mid - lo * mid
                        val x = i * colW + colW / 2f
                        drawLine(
                            color = waveColor,
                            start = Offset(x, y0),
                            end = Offset(x, y1),
                            strokeWidth = 1f,
                        )
                    }
                }
                val cx = cursor * w
                drawLine(
                    color = cursorColor,
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = CURSOR_STROKE_PX,
                )
            }
        }
        Spacer(Modifier.height(SPACER_HEIGHT.dp))
        // Spectrogram from cached PNG.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPECTROGRAM_HEIGHT.dp),
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

private const val WAVEFORM_HEIGHT = 96
private const val SPECTROGRAM_HEIGHT = 160
private const val SPACER_HEIGHT = 4
private const val CURSOR_STROKE_PX = 2f
