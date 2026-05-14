package com.sound2inat.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.inference.WindowPrediction
import kotlinx.coroutines.flow.StateFlow

/**
 * Cached mel-spectrogram view with the play cursor and (when supplied)
 * per-detection rectangles. The waveform strip was removed — at typical
 * Compose dp sizes individual peaks were unreadable and the strip just
 * stole vertical space from the spectrogram.
 */

@Suppress("FunctionNaming")
@Composable
internal fun WaveformAndSpectrogram(
    @Suppress("UNUSED_PARAMETER") peaks: FloatArray?,
    spectrogramPath: String?,
    durationMs: Long,
    positionFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.BIRD_FOCUSED,
    windowPreds: List<WindowPrediction> = emptyList(),
    species: List<SpeciesRow> = emptyList(),
    highlight: Long? = null,
    selectedStartMs: Long? = null,
    selectedEndMs: Long? = null,
    onWindowTap: (WindowPrediction) -> Unit = {},
) {
    // Position read is local to this subtree so the 50 ms tick from
    // MediaPlayer does not trigger recomposition of the entire Review screen.
    val positionMs by positionFlow.collectAsStateWithLifecycle()
    val cursor: Float = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val contentWidthPx = remember(durationMs) {
        ReviewSpectrogramTimeline.contentWidthPx(durationMs)
    }
    val density = LocalDensity.current
    val contentWidthDp = remember(contentWidthPx, density) {
        with(density) { contentWidthPx.toDp() }
    }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPECTROGRAM_HEIGHT.dp),
        ) {
            FrequencyAxis(
                range = displayRange,
                modifier = Modifier
                    .width(AXIS_WIDTH.dp)
                    .height(SPECTROGRAM_HEIGHT.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(SPECTROGRAM_HEIGHT.dp)
                    .horizontalScroll(scrollState)
                    // Tap-to-seek anywhere on the spectrogram. Detection-overlay
                    // taps are handled inside DetectionOverlays and don't reach
                    // here, so this fires for the bare-spectrogram regions only.
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            if (durationMs > 0L) {
                                onSeek(
                                    ReviewSpectrogramTimeline.seekMsFromTap(
                                        tapX = offset.x,
                                        horizontalScrollPx = scrollState.value.toFloat(),
                                        contentWidthPx = contentWidthPx,
                                        durationMs = durationMs,
                                    ),
                                )
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .width(contentWidthDp)
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
                        modifier = Modifier.fillMaxSize(),
                    )
                    val cursorColor = MaterialTheme.colorScheme.error
                    val selectionColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (selectedStartMs != null && selectedEndMs != null && durationMs > 0L) {
                            val left = (selectedStartMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
                            val right = (selectedEndMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
                            drawRect(
                                color = selectionColor.copy(alpha = 0.12f),
                                topLeft = Offset(left, 0f),
                                size = Size((right - left).coerceAtLeast(1f), size.height),
                            )
                            drawLine(
                                color = selectionColor,
                                start = Offset(left, 0f),
                                end = Offset(left, size.height),
                                strokeWidth = SELECTION_STROKE_PX,
                            )
                            drawLine(
                                color = selectionColor,
                                start = Offset(right, 0f),
                                end = Offset(right, size.height),
                                strokeWidth = SELECTION_STROKE_PX,
                            )
                        }
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
    }
}

private const val SPECTROGRAM_HEIGHT = 220
private const val AXIS_WIDTH = 56
private const val CURSOR_STROKE_PX = 2f
private const val SELECTION_STROKE_PX = 3f

@Composable
private fun FrequencyAxis(
    range: SpectrogramDisplayRange,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(range) {
        listOf(
            range.fMaxHz,
            lerpFrequency(range.fMinHz, range.fMaxHz, 0.75f),
            lerpFrequency(range.fMinHz, range.fMaxHz, 0.5f),
            lerpFrequency(range.fMinHz, range.fMaxHz, 0.25f),
            range.fMinHz,
        ).distinctBy { it }
    }
    Column(
        modifier = modifier.padding(end = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        ticks.forEach { hz ->
            Text(
                text = formatFrequencyLabel(hz),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun lerpFrequency(minHz: Int, maxHz: Int, fraction: Float): Int {
    val clamped = fraction.coerceIn(0f, 1f)
    return (maxHz - ((maxHz - minHz) * clamped)).toInt()
}

private fun formatFrequencyLabel(hz: Int): String =
    when {
        hz >= 1_000 -> {
            val khz = hz / 1_000f
            if (hz % 1_000 == 0) "${khz.toInt()}kHz" else String.format("%.1fkHz", khz)
        }
        else -> "${hz}Hz"
    }
