package com.sound2inat.app.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.permissions.LocalPermissionsController
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onDone: (draftId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val perms = LocalPermissionsController.current
    val hilt: RecordingViewModelHilt = hiltViewModel()
    val vm = remember(hilt) { hilt.factory(perms) }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recording") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.cancel()
                        onCancel()
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is RecordingUiState.Idle -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Starting…", style = MaterialTheme.typography.titleMedium)
                }
                is RecordingUiState.Recording -> RecordingBody(
                    s = s,
                    rmsHistoryFlow = vm.rmsHistory,
                    onStop = { vm.stop() },
                )
                is RecordingUiState.Done -> LaunchedEffect(s.draftId) { onDone(s.draftId) }
                is RecordingUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onCancel) { Text("Back") }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RecordingBody(
    s: RecordingUiState.Recording,
    rmsHistoryFlow: StateFlow<FloatArray>,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatElapsed(s.elapsedMs), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(24.dp))
            LiveWaveform(
                historyFlow = rmsHistoryFlow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WAVEFORM_HEIGHT_DP.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when (val gps = s.gps) {
                    is GpsStatus.Acquiring -> "GPS: acquiring…"
                    is GpsStatus.Fix -> "GPS: %.4f, %.4f%s".format(
                        gps.latitude,
                        gps.longitude,
                        gps.accuracyMeters?.let { " (±%.0f m)".format(it) }.orEmpty(),
                    )
                    is GpsStatus.NoFix -> "No GPS fix; draft will be saved without location"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.warningSoftLimit) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Long recording — auto-stop at 10:00",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        FilledIconButton(
            onClick = onStop,
            shape = CircleShape,
            modifier = Modifier.size(STOP_BUTTON_DP.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = "Stop",
                modifier = Modifier.size(STOP_ICON_DP.dp),
            )
        }
    }
}

/**
 * Live amplitude bars driven by the recorder's rolling RMS history. Each entry
 * in the history is rendered as a vertical line whose height is proportional
 * to the RMS value, scaled with sqrt to lift quiet passages out of the noise
 * floor (raw RMS for typical bird calls sits well below 0.1).
 *
 * The flow is collected locally here so the per-block tick (~85 ms at 48 kHz)
 * does not recompose any sibling Text widgets.
 */
@Suppress("FunctionNaming")
@Composable
private fun LiveWaveform(
    historyFlow: StateFlow<FloatArray>,
    modifier: Modifier = Modifier,
) {
    val history by historyFlow.collectAsState()
    val barColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        // Baseline so the strip is visible even before any audio arrives.
        drawLine(
            color = baselineColor,
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1f,
        )
        if (history.isEmpty()) return@Canvas
        val cap = com.sound2inat.recorder.Recorder.HISTORY_SIZE
        val barWidth = w / cap
        // Newest sample anchors to the right edge — old data scrolls left.
        val start = cap - history.size
        for (i in history.indices) {
            val rms = history[i]
            // sqrt-shaping makes -30 dB content readable; clamp at 1.0 so loud
            // peaks don't spill outside the strip.
            val mag = sqrt(rms.coerceIn(0f, 1f))
            val halfH = (mag * mid * WAVEFORM_GAIN).coerceAtMost(mid)
            val x = (start + i) * barWidth + barWidth / 2f
            drawLine(
                color = barColor,
                start = Offset(x, mid - halfH),
                end = Offset(x, mid + halfH),
                strokeWidth = (barWidth * BAR_FILL).coerceAtLeast(1f),
            )
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val WAVEFORM_HEIGHT_DP = 96
private const val WAVEFORM_GAIN = 1.5f
private const val BAR_FILL = 0.6f
private const val STOP_BUTTON_DP = 96
private const val STOP_ICON_DP = 48
