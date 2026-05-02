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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.storage.DraftStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Outer Review screen — owns the [HorizontalPager] over every saved draft
 * (Home order, newest first) so the user can swipe between recordings
 * without going back to Home. Each page builds its own [ReviewViewModel]
 * via [ReviewViewModelFactory] and releases it when the page leaves
 * composition (HorizontalPager keeps a small offscreen window cached).
 *
 * Swipe-right → newer recording (up the Home list);
 * swipe-left → older recording (down).
 */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
) {
    val pagerVm: ReviewPagerViewModel = hiltViewModel()
    val draftIds by pagerVm.orderedDraftIds.collectAsState()

    if (draftIds.isEmpty()) {
        // Either the list flow has not emitted yet or every draft has been
        // deleted out from under us. Render nothing — the outer nav graph
        // is responsible for popping back if the user truly has no drafts.
        return
    }

    val initialIndex = remember(draftIds, pagerVm.initialDraftId) {
        draftIds.indexOf(pagerVm.initialDraftId).coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { draftIds.size }

    HorizontalPager(
        state = pagerState,
        key = { idx -> draftIds.getOrElse(idx) { idx.toString() } },
        modifier = Modifier.fillMaxSize(),
    ) { pageIndex ->
        val draftId = draftIds.getOrNull(pageIndex) ?: return@HorizontalPager
        val vm = remember(draftId) { pagerVm.factory.create(draftId) }
        DisposableEffect(draftId) {
            onDispose { vm.release() }
        }
        // Pause whichever offscreen page was playing as soon as the user
        // starts swiping; resume requires an explicit Play tap on the new
        // page anyway, so this never fights the user's intent.
        val isActive = pagerState.currentPage == pageIndex
        LaunchedEffect(isActive) {
            if (!isActive) vm.pause()
        }
        ReviewPage(
            vm = vm,
            filesDir = pagerVm.factory.filesDir,
            onBack = onBack,
        )
    }
}

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPage(
    vm: ReviewViewModel,
    filesDir: java.io.File,
    onBack: () -> Unit,
) {
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
        if (state.audioPath != null) vm.ensureVisuals(filesDir)
    }

    var pickerVisible by remember { mutableStateOf(false) }
    val isAnalysisRunning = state.inferenceProgress != null || state.perchProgress != null
    val uploadedUrls = remember(state.inatObservations) { state.inatObservations.toMap() }

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
        bottomBar = { SubmitBottomBar(state = state, vm = vm) },
    ) { padding ->
        // PullToRefreshBox handles the gesture animation; we never set
        // isRefreshing=true (no async work to await) — instead we treat
        // onRefresh as "user requested re-analysis" and pop the model picker.
        // While another run is in flight, swallow the gesture entirely.
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = {
                if (!isAnalysisRunning && state.audioPath != null) {
                    pickerVisible = true
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        // Single LazyColumn covers the whole screen body — header, player,
        // visuals, banner, Submit and species list all scroll together so the
        // species list is no longer pinned in a tiny inner viewport.
        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            item { DenoisePreviewToggle(state, vm, filesDir) }
            if (state.inferenceProgress != null) {
                item { InferenceProgressBlock(state.inferenceProgress!!) }
            }
            state.perchProgress?.let { p ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Analysing with Perch… ${(p * PERCENT).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { p.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            state.perchError?.let { err ->
                item {
                    Text(
                        "Perch: $err",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
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
            if (state.inatSubmission is InatSubmissionState.Failed) {
                item {
                    Text(
                        "Upload failed: ${(state.inatSubmission as InatSubmissionState.Failed).message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (state.inatObservations.isNotEmpty()) {
                item {
                    UploadedBanner(
                        observations = state.inatObservations,
                        speciesRows = state.species,
                    )
                    HorizontalDivider()
                }
            }
            val (likelySpecies, unlikelySpecies) = state.species.partition {
                it.regionalStatus != RegionalStatus.NOT_CONFIRMED
            }

            item {
                Text(
                    "Detected species",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider() }

            items(likelySpecies, key = { it.detectionId }) { row ->
                SpeciesListItem(
                    row = row,
                    isHighlighted = highlight == row.detectionId,
                    uploadedUrl = uploadedUrls[row.taxonScientificName],
                    onClick = {
                        vm.seekTo(row.firstSeenMs)
                        vm.highlight(row.detectionId)
                    },
                    onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
                    onExpandDetail = {
                        uploadedUrls[row.taxonScientificName]?.let { url ->
                            vm.loadObservationDetail(row.detectionId, url)
                        }
                    },
                    onCollapseDetail = { vm.collapseObservationDetail(row.detectionId) },
                )
                HorizontalDivider()
            }
            if (likelySpecies.isEmpty() && state.inferenceProgress == null && unlikelySpecies.isEmpty()) {
                item {
                    Text(
                        "No species detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (unlikelySpecies.isNotEmpty()) {
                item {
                    Text(
                        "Unlikely — not observed nearby",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item { HorizontalDivider() }
                items(unlikelySpecies, key = { it.detectionId }) { row ->
                    SpeciesListItem(
                        row = row,
                        isHighlighted = highlight == row.detectionId,
                        uploadedUrl = uploadedUrls[row.taxonScientificName],
                        onClick = {
                            vm.seekTo(row.firstSeenMs)
                            vm.highlight(row.detectionId)
                        },
                        onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
                        onExpandDetail = {
                            uploadedUrls[row.taxonScientificName]?.let { url ->
                                vm.loadObservationDetail(row.detectionId, url)
                            }
                        },
                        onCollapseDetail = { vm.collapseObservationDetail(row.detectionId) },
                    )
                    HorizontalDivider()
                }
            }

        }
        }
    }

    if (pickerVisible) {
        ModelPickerDialog(
            isPerchInstalled = state.isPerchInstalled,
            onPickBirdnet = {
                pickerVisible = false
                vm.reanalyzeBirdnet()
            },
            onPickPerch = {
                pickerVisible = false
                vm.analyzeWithPerch()
            },
            onDismiss = { pickerVisible = false },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ModelPickerDialog(
    isPerchInstalled: Boolean,
    onPickBirdnet: () -> Unit,
    onPickPerch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-analyze recording") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick a model to run again. New detections are merged with " +
                        "existing ones — matching species pick up an extra source badge.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isPerchInstalled) {
                    Text(
                        "Perch is not installed — install it from Settings to enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPickBirdnet) { Text("BirdNET") }
                TextButton(
                    onClick = onPickPerch,
                    enabled = isPerchInstalled,
                ) { Text("Perch") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun DenoisePreviewToggle(
    state: ReviewUiState,
    vm: ReviewViewModel,
    filesDir: java.io.File,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val coScope = rememberCoroutineScope()
    val tooltipText = when {
        state.denoisingInProgress -> "Building denoised preview…"
        state.denoisePreviewEnabled ->
            "Spectrogram and playback reflect the noise-reduction pipeline. " +
                "Disable to compare against the original recording."
        else ->
            "Toggle to preview the cleaned-up audio. The original recording " +
                "is always preserved."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Denoise",
            style = MaterialTheme.typography.bodyMedium,
        )
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(tooltipText) } },
            state = tooltipState,
        ) {
            IconButton(
                onClick = { coScope.launch { tooltipState.show() } },
                modifier = Modifier.size(DENOISE_HELP_SIZE_DP.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "About denoise",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DENOISE_HELP_ICON_SIZE_DP.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
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

@Suppress("FunctionNaming")
@Composable
private fun SubmitBottomBar(state: ReviewUiState, vm: ReviewViewModel) {
    val alreadyUploaded = state.status == DraftStatus.UPLOADED ||
        state.inatSubmission is InatSubmissionState.Done
    val selectedCount = state.species.count { it.isSelected }
    val inProgress = state.inatSubmission is InatSubmissionState.InProgress
    val canSubmit = !alreadyUploaded && selectedCount > 0 && !inProgress

    val label = when {
        inProgress -> "Uploading…"
        alreadyUploaded -> "Already uploaded"
        selectedCount == 0 -> "Select species to submit"
        selectedCount == 1 -> "Submit 1 species to iNaturalist"
        else -> "Submit $selectedCount species to iNaturalist"
    }

    Surface(shadowElevation = 4.dp) {
        Button(
            onClick = { vm.submitToINaturalist() },
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(label)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun UploadedBanner(
    observations: List<Pair<String, String>>,
    speciesRows: List<SpeciesRow>,
) {
    val uriHandler = LocalUriHandler.current
    val commonNameByScientific = remember(speciesRows) {
        speciesRows.associate { it.taxonScientificName to it.taxonCommonName }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (observations.size == 1) "1 observation already uploaded"
                else "${observations.size} observations already uploaded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        observations.forEach { (scientificName, url) ->
            val commonName = commonNameByScientific[scientificName]
            val displayName = if (commonName != null) "$commonName · $scientificName" else scientificName
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { uriHandler.openUri(url) }) {
                    Text("View", style = MaterialTheme.typography.bodySmall)
                }
            }
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

@Suppress("FunctionNaming")
@Composable
private fun RegionalStatusIcon(status: RegionalStatus) {
    val tint = when (status) {
        RegionalStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
        RegionalStatus.NOT_CONFIRMED -> MaterialTheme.colorScheme.error
        RegionalStatus.UNVERIFIED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val desc = when (status) {
        RegionalStatus.CONFIRMED -> "Observed in region"
        RegionalStatus.NOT_CONFIRMED -> "Not observed in region"
        RegionalStatus.UNVERIFIED -> "Regional check unavailable"
    }
    Icon(
        imageVector = Icons.Outlined.Public,
        contentDescription = desc,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun SpeciesListItem(
    row: SpeciesRow,
    isHighlighted: Boolean,
    uploadedUrl: String?,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onExpandDetail: () -> Unit,
    onCollapseDetail: () -> Unit,
) {
    val containerColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val uriHandler = LocalUriHandler.current
    ListItem(
        modifier = Modifier
            .clickable {
                if (uploadedUrl != null) {
                    when (row.observationDetailState) {
                        is ObservationDetailLoadState.NotLoaded -> onExpandDetail()
                        else -> onCollapseDetail()
                    }
                } else {
                    onClick()
                    onCheckedChange(!row.isSelected)
                }
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.taxonScientificName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uploadedUrl != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Already uploaded · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "View observation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri(uploadedUrl) },
                        )
                    }
                    when (val detailState = row.observationDetailState) {
                        is ObservationDetailLoadState.NotLoaded -> {}
                        is ObservationDetailLoadState.Loading -> {
                            Spacer(Modifier.height(4.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        is ObservationDetailLoadState.Loaded -> {
                            val d = detailState.detail
                            Spacer(Modifier.height(4.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${qualityGradeLabel(d.qualityGrade)} · ${d.agreeingIdCount} IDs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                d.comments.forEach { c ->
                                    Text(
                                        "\"${c.body}\" — ${c.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is ObservationDetailLoadState.Error -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detailState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    val pct = (row.maxConfidence * PERCENT).toInt()
                    Text(
                        "${confidenceLabel(row.maxConfidence)} · $pct% · ${row.detectedWindows} audio fragments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.confidenceBySource.isNotEmpty() || row.regionalStatus != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            row.confidenceBySource.entries
                                .sortedByDescending { it.value }
                                .forEach { (src, conf) ->
                                    SourceBadge(src, conf)
                                }
                            row.regionalStatus?.let { RegionalStatusIcon(it) }
                        }
                    }
                }
            }
        },
        trailingContent = {
            if (uploadedUrl != null) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Uploaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Checkbox(checked = row.isSelected, onCheckedChange = onCheckedChange)
            }
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

private fun confidenceLabel(confidence: Float): String = when {
    confidence >= 0.9f -> "High confidence"
    confidence >= 0.7f -> "Likely"
    confidence >= 0.5f -> "Uncertain"
    else -> "Low confidence"
}

private fun qualityGradeLabel(grade: String): String = when (grade) {
    "research" -> "Research Grade"
    "needs_id" -> "Needs ID"
    "casual" -> "Casual"
    else -> grade.replace('_', ' ').replaceFirstChar { it.uppercase() }
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
private const val DENOISE_HELP_SIZE_DP = 24
private const val DENOISE_HELP_ICON_SIZE_DP = 18
