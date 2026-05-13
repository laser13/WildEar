package com.sound2inat.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inference.WindowPrediction
import kotlinx.coroutines.flow.StateFlow

/**
 * Cached mel-spectrogram view with the play cursor and (when supplied)
 * per-detection rectangles. The waveform strip was removed — at typical
 * Compose dp sizes individual peaks were unreadable and the strip just
 * stole vertical space from the spectrogram.
 */
private val PALETTE_OPTIONS = listOf(
    SpectrogramPalette.VIRIDIS,
    SpectrogramPalette.MAGMA,
    SpectrogramPalette.GRAY,
)

private val PALETTE_SHORT_LABELS = mapOf(
    SpectrogramPalette.VIRIDIS to "Viridis",
    SpectrogramPalette.MAGMA to "Magma",
    SpectrogramPalette.GRAY to "Gray",
)

private val GAIN_OPTIONS = listOf(-20f, -10f, 0f, 10f, 20f)

@Suppress("FunctionNaming")
@Composable
internal fun WaveformAndSpectrogram(
    @Suppress("UNUSED_PARAMETER") peaks: FloatArray?,
    spectrogramPath: String?,
    durationMs: Long,
    positionFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.BIRD_FOCUSED,
    onDisplayRangeChange: (SpectrogramDisplayRange) -> Unit = {},
    config: ReviewSpectrogramConfig = ReviewSpectrogramConfig.BirdDefault,
    onPaletteChange: (SpectrogramPalette) -> Unit = {},
    onGainChange: (Float) -> Unit = {},
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            items(PALETTE_OPTIONS) { palette ->
                FilterChip(
                    selected = config.palette == palette,
                    onClick = { onPaletteChange(palette) },
                    label = { Text(PALETTE_SHORT_LABELS[palette] ?: palette.name) },
                )
            }
        }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            items(GAIN_OPTIONS) { gain ->
                FilterChip(
                    selected = config.gainDb == gain,
                    onClick = { onGainChange(gain) },
                    label = { Text(formatGainLabel(gain)) },
                )
            }
        }
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

private const val SPECTROGRAM_HEIGHT = 220
private const val AXIS_WIDTH = 56
private const val CURSOR_STROKE_PX = 2f
private const val SELECTION_STROKE_PX = 3f

private fun formatGainLabel(gainDb: Float): String =
    when {
        gainDb > 0f -> "+${gainDb.toInt()}"
        gainDb == 0f -> "0"
        else -> gainDb.toInt().toString()
    }

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
