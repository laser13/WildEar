package com.sound2inat.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
    val denoisedSpectrogramFile by vm.denoisedSpectrogramFile.collectAsState()
    val waveformPeaks by vm.waveformPeaks.collectAsState()
    val windowPreds by vm.windowPreds.collectAsState()
    val highlight by vm.highlight.collectAsState()
    val effectiveSpectrogram = if (state.denoisePreviewEnabled && denoisedSpectrogramFile != null) {
        denoisedSpectrogramFile
    } else {
        spectrogramFile
    }

    LaunchedEffect(state.audioPath) {
        if (state.audioPath != null) vm.ensureVisuals(hilt.filesDir)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.delete(onDeleted = onBack) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        // Single LazyColumn covers the whole screen body — header, player,
        // visuals, banner, Submit and species list all scroll together so the
        // species list is no longer pinned in a tiny inner viewport.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { HeaderBlock(state) }
            item { PlayerControls(state = state, vm = vm) }
            item {
                WaveformAndSpectrogram(
                    peaks = waveformPeaks,
                    spectrogramPath = effectiveSpectrogram?.takeIf { it.exists() }?.absolutePath,
                    durationMs = state.durationMs,
                    positionFlow = vm.playerPosition,
                    windowPreds = windowPreds,
                    species = state.species,
                    highlight = highlight,
                    onWindowTap = vm::onWindowTapped,
                    onSeek = vm::seekTo,
                )
            }
            item { DenoisePreviewToggle(state, vm, hilt.filesDir) }
            if (state.inferenceProgress != null) {
                item { InferenceProgressBlock(state.inferenceProgress!!) }
            } else if (state.audioPath != null && state.status != DraftStatus.UPLOADED) {
                item {
                    OutlinedButton(
                        onClick = { vm.reanalyze() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Re-analyze")
                    }
                }
            }
            state.inferenceError?.let { err ->
                item {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item { SubmitSection(state = state, vm = vm) }
            item {
                Text(
                    "Detected species",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider() }

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
    }
}

@Suppress("FunctionNaming")
@Composable
private fun HeaderBlock(state: ReviewUiState) {
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
}

@Suppress("FunctionNaming")
@Composable
private fun DenoisePreviewToggle(
    state: ReviewUiState,
    vm: ReviewViewModel,
    filesDir: java.io.File,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Show denoised", style = MaterialTheme.typography.bodyMedium)
            val sub = when {
                state.denoisingInProgress -> "Building preview…"
                state.denoisePreviewEnabled -> "Spectrogram and audio reflect the noise-reduction pipeline"
                else -> "Toggle to compare the cleaned-up audio against the original"
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.denoisePreviewEnabled,
            onCheckedChange = { vm.setDenoisePreviewEnabled(it, filesDir) },
            enabled = !state.denoisingInProgress,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun InferenceProgressBlock(progress: Float) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Analyzing audio…", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun SubmitSection(state: ReviewUiState, vm: ReviewViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val sub = state.inatSubmission) {
            is InatSubmissionState.InProgress -> Text(
                "Uploading to iNaturalist…",
                style = MaterialTheme.typography.bodyMedium,
            )
            is InatSubmissionState.Done -> {
                Text(
                    "Uploaded ${sub.urls.size} observation(s):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                sub.urls.forEach { url -> Text(url, style = MaterialTheme.typography.bodySmall) }
            }
            is InatSubmissionState.Failed -> Text(
                "Upload failed: ${sub.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> if (state.inatObservations.isNotEmpty()) {
                Text(
                    "Already uploaded ${state.inatObservations.size} observation(s):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.inatObservations.forEach { (taxon, url) ->
                    Text("$taxon → $url", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // The Save button used to live here. It served two purposes at once
        // — flip status to REVIEWED, and navigate back — and forced the
        // user to round-trip through Home before Submit became enabled.
        // The VM now auto-promotes status after inference, so Save is dead
        // weight. Submit is gated only by the user's checkbox selection.
        val alreadyUploaded = state.status == DraftStatus.UPLOADED ||
            state.inatSubmission is InatSubmissionState.Done
        val canSubmit = !alreadyUploaded &&
            state.species.any { it.isSelected } &&
            state.inatSubmission !is InatSubmissionState.InProgress
        Button(
            onClick = { vm.submitToINaturalist() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                if (alreadyUploaded) "Uploaded to iNaturalist" else "Submit to iNaturalist",
            )
        }
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
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "Pause" else "Play")
            }
            Text("${formatMs(positionMs)} / ${formatMs(state.durationMs)}")
        }
        if (state.playback is PlaybackState.Error) {
            Text(state.playback.message, color = MaterialTheme.colorScheme.error)
        }
    }
    // Slider removed — the spectrogram already shows position via the red
    // cursor line, and tap-to-seek on the spectrogram itself replaces the
    // drag-to-seek functionality. See WaveformAndSpectrogram.
}

@Suppress("FunctionNaming", "LongMethod")
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
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(PHOTO_SIZE_DP.dp)
                    .clip(RoundedCornerShape(PHOTO_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (row.taxonPhotoUrl != null) {
                    AsyncImage(
                        model = row.taxonPhotoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Outlined.MicNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(row.taxonCommonName ?: row.taxonScientificName)
        },
        supportingContent = {
            Column {
                val pct = (row.maxConfidence * PERCENT).toInt()
                Text("$pct%  ·  ${row.detectedWindows} windows  ·  ${row.taxonScientificName}")
                if (row.confidenceBySource.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.confidenceBySource.entries
                            .sortedByDescending { it.value }
                            .forEach { (src, conf) ->
                                SourceBadge(src, conf)
                            }
                    }
                }
            }
        },
        trailingContent = {
            Checkbox(checked = row.isSelected, onCheckedChange = onCheckedChange)
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun SourceBadge(source: String, confidence: Float) {
    val pct = (confidence * PERCENT).toInt()
    val label = displayNameFor(source)
    Text(
        text = "$label $pct%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(BADGE_CORNER_DP.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(BADGE_CORNER_DP.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun displayNameFor(source: String): String = when (source) {
    "birdnet_v2_4" -> "BirdNET"
    "perch_v2" -> "Perch"
    else -> source
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
private const val BADGE_CORNER_DP = 8
private const val PHOTO_SIZE_DP = 56
private const val PHOTO_CORNER_DP = 4
