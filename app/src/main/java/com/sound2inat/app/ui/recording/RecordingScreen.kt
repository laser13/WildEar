package com.sound2inat.app.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.permissions.LocalPermissionsController
import kotlinx.coroutines.flow.SharedFlow

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
                    audioBlocks = vm.audioBlocks,
                    sampleRateHz = vm.sampleRateHz,
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

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun RecordingBody(
    s: RecordingUiState.Recording,
    audioBlocks: SharedFlow<FloatArray>,
    sampleRateHz: Int,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header strip: elapsed time + GPS status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatElapsed(s.elapsedMs), style = MaterialTheme.typography.headlineMedium)
            Text(
                gpsLabel(s.gps),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Live spectrogram — Merlin-style scrolling heatmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(SPECTROGRAM_WEIGHT),
        ) {
            LiveSpectrogramView(
                audioBlocks = audioBlocks,
                sampleRateHz = sampleRateHz,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (s.backlogWindows > 0) {
            Text(
                "Analysis catching up… (${s.backlogWindows})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Live detections list (Merlin per-species cards)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(LIVE_CARDS_WEIGHT),
        ) {
            if (s.liveCards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Listening for birds…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.liveCards, key = { it.scientificName }) { card ->
                        LiveCardRow(card = card)
                    }
                }
            }
        }

        if (s.warningSoftLimit) {
            Text(
                "Long recording — auto-stop at 10:00",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
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

@Suppress("FunctionNaming")
@Composable
private fun LiveCardRow(card: LiveCard) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.commonName ?: card.scientificName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (card.commonName != null) {
                    Text(
                        card.scientificName,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("×${card.count}") },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(
                "%.0f%%".format(card.peakConfidence * PERCENT_SCALE),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun gpsLabel(gps: GpsStatus): String = when (gps) {
    is GpsStatus.Acquiring -> "GPS: acquiring…"
    is GpsStatus.Fix -> "GPS: %.4f, %.4f%s".format(
        gps.latitude,
        gps.longitude,
        gps.accuracyMeters?.let { " (±%.0f m)".format(it) }.orEmpty(),
    )
    is GpsStatus.NoFix -> "No GPS fix"
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val STOP_BUTTON_DP = 96
private const val STOP_ICON_DP = 48
private const val SPECTROGRAM_WEIGHT = 1f
private const val LIVE_CARDS_WEIGHT = 1f
private const val PERCENT_SCALE = 100f
