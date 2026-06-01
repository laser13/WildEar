package com.sound2inat.app.ui.review

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
import com.sound2inat.app.ui.spectrogram.DisplayRangeSpec
import com.sound2inat.audio.SpectrogramDisplayPlane
import com.sound2inat.inference.WindowPrediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.ln

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
    displayPlane: SpectrogramDisplayPlane?,
    spectrogramConfig: ReviewSpectrogramConfig,
    durationMs: Long,
    positionFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    visualsLoading: Boolean = false,
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
    var autoFollow by remember(durationMs) { mutableStateOf(true) }

    // Disarm auto-follow as soon as the user starts a manual scroll gesture;
    // PlayheadAutoScroll.decide() takes care of re-arming when the cursor
    // catches up to the visible window again. durationMs is in the key set
    // because autoFollow is `remember(durationMs)` — when the draft changes,
    // a fresh MutableState is created and this watcher must re-bind to it.
    LaunchedEffect(scrollState, durationMs) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collect { inProgress ->
                if (inProgress) autoFollow = false
            }
    }

    // Apply auto-follow decision on every position tick: keep the cursor
    // centered during playback, but only if auto-follow is currently armed
    // (or the cursor returned into the visible window after a manual scroll —
    // see PlayheadAutoScroll). One long-lived coroutine consumes positionMs
    // through snapshotFlow so we don't restart the effect ~20 times/second.
    LaunchedEffect(durationMs, contentWidthPx, scrollState) {
        if (durationMs <= 0L || contentWidthPx <= 0) return@LaunchedEffect
        snapshotFlow { positionMs }.collect { pos ->
            val cursorPx = (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * contentWidthPx
            val decision = PlayheadAutoScroll.decide(
                autoFollow = autoFollow,
                cursorPx = cursorPx,
                currentScroll = scrollState.value,
                viewportSize = scrollState.viewportSize,
                maxScroll = scrollState.maxValue,
            )
            if (decision.newAutoFollow != autoFollow) {
                autoFollow = decision.newAutoFollow
            }
            decision.targetScroll?.let { target ->
                scrollState.scrollTo(target)
            }
        }
    }

    val axisBackdrop = MaterialTheme.colorScheme.surface.copy(alpha = AXIS_BACKDROP_ALPHA)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Spectrogram scrolls under a translucent frequency-axis overlay
        // (Merlin / BirdNET-style), so the chart uses the full content width
        // and the kHz labels float on top of the leftmost edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPECTROGRAM_HEIGHT.dp)
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
                    .fillMaxSize()
                    .horizontalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier
                        .width(contentWidthDp)
                        .height(SPECTROGRAM_HEIGHT.dp),
                ) {
                    // Build the Bitmap off the Main thread to avoid jank.
                    // produceState cancels and restarts whenever displayPlane
                    // or spectrogramConfig change.
                    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(
                        initialValue = null,
                        key1 = displayPlane,
                        key2 = spectrogramConfig,
                    ) {
                        value = withContext(Dispatchers.Default) {
                            displayPlane
                                ?.takeIf { it.width > 0 && it.height > 0 }
                                ?.let { plane ->
                                    previewFromDisplayPlane(plane, spectrogramConfig)
                                }
                                ?.takeIf { it.width > 0 && it.height > 0 }
                                ?.let { preview ->
                                    Bitmap.createBitmap(
                                        preview.argb,
                                        preview.width,
                                        preview.height,
                                        Bitmap.Config.ARGB_8888,
                                    ).asImageBitmap()
                                }
                        }
                    }
                    bitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
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
            // Floating frequency axis: positioned over the leftmost edge of the
            // scrolling spectrogram, with a translucent surface backdrop so the
            // kHz labels stay legible against any palette. The axis itself does
            // not consume pointer events, so tap-to-seek still works through it.
            FrequencyAxis(
                rangeSpec = spectrogramConfig.effectiveRangeSpec,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(AXIS_WIDTH.dp)
                    .fillMaxHeight()
                    .background(axisBackdrop),
            )
            if (visualsLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
    }
}

private const val SPECTROGRAM_HEIGHT = 220
private const val AXIS_WIDTH = 56
private const val AXIS_BACKDROP_ALPHA = 0.72f
private const val CURSOR_STROKE_PX = 2f
private const val SELECTION_STROKE_PX = 3f

@Composable
private fun FrequencyAxis(
    rangeSpec: DisplayRangeSpec,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(rangeSpec) {
        listOf(
            rangeSpec.fMaxHz,
            axisTickFromMax(rangeSpec.fMinHz, rangeSpec.fMaxHz, 0.25f),
            axisTickFromMax(rangeSpec.fMinHz, rangeSpec.fMaxHz, 0.5f),
            axisTickFromMax(rangeSpec.fMinHz, rangeSpec.fMaxHz, 0.75f),
            rangeSpec.fMinHz,
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

/**
 * Returns an intermediate axis-tick frequency at [fraction] of the way **downward
 * from `maxHz` to `minHz`**, mapped on a logarithmic scale so the ticks line up
 * with the mel-spectrogram's log-frequency rows. Without this, a linear axis
 * would mislabel where mid frequencies actually sit — e.g. for Full (0–24 kHz)
 * the centre of the chart is ~500 Hz on log scale, not 12 kHz on a linear one.
 *
 * `minHz == 0` is coerced to 1 so `ln` is defined; the resulting near-bottom
 * label rounds down to "0Hz" anyway via [formatFrequencyLabel].
 */
private fun axisTickFromMax(minHz: Int, maxHz: Int, fraction: Float): Int {
    val safeMin = minHz.coerceAtLeast(1).toDouble()
    val safeMax = maxHz.coerceAtLeast((safeMin + 1).toInt()).toDouble()
    val clamped = fraction.coerceIn(0f, 1f).toDouble()
    val logMin = ln(safeMin)
    val logMax = ln(safeMax)
    val logValue = logMax - clamped * (logMax - logMin)
    return kotlin.math.exp(logValue).toInt()
}

private fun formatFrequencyLabel(hz: Int): String =
    when {
        hz >= 1_000 -> {
            val khz = hz / 1_000f
            if (hz % 1_000 == 0) "${khz.toInt()}kHz" else String.format(java.util.Locale.ROOT, "%.1fkHz", khz)
        }
        else -> "${hz}Hz"
    }
