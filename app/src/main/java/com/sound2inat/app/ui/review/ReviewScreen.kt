package com.sound2inat.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.storage.DraftStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
) {
    val hilt: ReviewViewModelHilt = hiltViewModel()
    val vm = hilt.delegate
    val state by vm.state.collectAsState()
    val spectrogramFile by vm.spectrogramFile.collectAsState()
    val waveformPeaks by vm.waveformPeaks.collectAsState()
    val windowPreds by vm.windowPreds.collectAsState()
    val highlight by vm.highlight.collectAsState()

    LaunchedEffect(state.audioPath) {
        if (state.audioPath != null) vm.ensureVisuals(hilt.filesDir)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Review") },
            navigationIcon = {
                IconButton(onClick = onBack) { Text("←") }
            },
            actions = {
                IconButton(onClick = { vm.delete(onDeleted = onBack) }) { Text("🗑") }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(formatTimestamp(state.recordedAtUtcMs), style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.latitude != null && state.longitude != null) {
                    "GPS: %.4f, %.4f".format(state.latitude, state.longitude)
                } else {
                    "No location"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PlayerControls(state = state, vm = vm)

        WaveformAndSpectrogram(
            peaks = waveformPeaks,
            spectrogramPath = spectrogramFile?.takeIf { it.exists() }?.absolutePath,
            durationMs = state.durationMs,
            positionFlow = vm.playerPosition,
            windowPreds = windowPreds,
            species = state.species,
            highlight = highlight,
            onWindowTap = vm::onWindowTapped,
        )

        if (state.inferenceProgress != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Analyzing audio…", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.inferenceProgress?.coerceIn(0f, 1f) ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.inferenceError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Text(
            "Detected species",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.species, key = { it.detectionId }) { row ->
                SpeciesListItem(
                    row = row,
                    isHighlighted = highlight == row.detectionId,
                    onClick = { vm.highlight(row.detectionId) },
                    onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
                )
                HorizontalDivider()
            }
            if (state.species.isEmpty() && state.inferenceProgress == null) {
                item {
                    Text(
                        "No species detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        Button(
            onClick = { vm.save(onSaved = onBack) },
            enabled = state.status == DraftStatus.PENDING_REVIEW,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
        ) { Text("Save") }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun PlayerControls(state: ReviewUiState, vm: ReviewViewModel) {
    val durationMs = state.durationMs.coerceAtLeast(1L)
    val positionMs = when (val pb = state.playback) {
        is PlaybackState.Playing -> pb.positionMs
        is PlaybackState.Paused -> pb.positionMs
        else -> 0L
    }
    val isPlaying = state.playback is PlaybackState.Playing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (isPlaying) vm.pause() else vm.play() }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Text("${formatMs(positionMs)} / ${formatMs(state.durationMs)}")
        }
        Slider(
            value = positionMs.toFloat() / durationMs.toFloat(),
            onValueChange = { fraction ->
                vm.seekTo((fraction * state.durationMs).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.playback is PlaybackState.Error) {
            Text(state.playback.message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SpeciesListItem(
    row: SpeciesRow,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(containerColor),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(row.taxonCommonName ?: row.taxonScientificName)
        },
        supportingContent = {
            val pct = (row.maxConfidence * PERCENT).toInt()
            Text("$pct%  ·  ${row.detectedWindows} windows  ·  ${row.taxonScientificName}")
        },
        trailingContent = {
            Checkbox(checked = row.isSelected, onCheckedChange = onCheckedChange)
        },
    )
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val PERCENT = 100f
