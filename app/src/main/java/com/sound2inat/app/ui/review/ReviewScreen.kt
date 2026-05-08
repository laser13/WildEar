package com.sound2inat.app.ui.review

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sound2inat.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import androidx.browser.customtabs.CustomTabsIntent
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.ui.theme.detectionCardLikelyDark
import com.sound2inat.app.ui.theme.detectionCardLikelyLight
import com.sound2inat.app.ui.theme.detectionCardUnlikelyDark
import com.sound2inat.app.ui.theme.detectionCardUnlikelyLight
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.storage.DraftStatus
import kotlinx.coroutines.launch
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
    val draftIds by pagerVm.orderedDraftIds.collectAsStateWithLifecycle()

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
    val state by vm.state.collectAsStateWithLifecycle()
    val spectrogramFile by vm.spectrogramFile.collectAsStateWithLifecycle()
    val waveformPeaks by vm.waveformPeaks.collectAsStateWithLifecycle()
    val windowPreds by vm.windowPreds.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()

    LaunchedEffect(state.audioPath) {
        if (state.audioPath != null) vm.ensureVisuals(filesDir)
    }

    var pickerVisible by remember { mutableStateOf(false) }
    var detailsRow by remember { mutableStateOf<SpeciesRow?>(null) }
    val isAnalysisRunning = state.inferenceProgress != null || state.perchProgress != null
    val uploadedUrls = remember(state.inatObservations) { state.inatObservations.associate { it.scientificName to it.url } }
    val uploadedIds  = remember(state.inatObservations) { state.inatObservations.associate { it.scientificName to it.observationId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_review)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.delete(onDeleted = onBack) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete))
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
                        spectrogramPath = spectrogramFile?.takeIf { it.exists() }?.absolutePath,
                        durationMs = state.durationMs,
                        positionFlow = vm.playerPosition,
                        windowPreds = windowPreds,
                        species = state.species,
                        highlight = highlight,
                        onWindowTap = vm::onWindowTapped,
                        onSeek = vm::seekTo,
                    )
                }
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
                        stringResource(R.string.review_section_detected_wildlife),
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
                        onRowClick = { detailsRow = row },
                        onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
                    )
                    HorizontalDivider()
                }
                if (likelySpecies.isEmpty() && state.inferenceProgress == null && unlikelySpecies.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.review_no_species),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                if (unlikelySpecies.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.review_unlikely_section),
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
                            onRowClick = { detailsRow = row },
                            onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
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

    detailsRow?.let { snapshot ->
        val liveRow = state.species.find { it.detectionId == snapshot.detectionId } ?: snapshot
        val obsEntry = state.inatObservations.find { it.scientificName == snapshot.taxonScientificName }

        LaunchedEffect(snapshot.detectionId) {
            obsEntry?.let { vm.loadObservationDetail(snapshot.detectionId, it.observationId) }
        }

        SpeciesDetailsSheet(
            row = liveRow,
            onDismiss = { detailsRow = null },
            onSeekTo = { ms ->
                vm.seekTo(ms)
                vm.highlight(snapshot.detectionId)
            },
            uploadedUrl = obsEntry?.url,
            onLoadDetail = obsEntry?.let { { vm.loadObservationDetail(snapshot.detectionId, it.observationId) } },
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
        title = { Text(stringResource(R.string.dialog_reanalyze_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.dialog_reanalyze_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isPerchInstalled) {
                    Text(
                        stringResource(R.string.dialog_perch_not_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPickBirdnet) { Text(stringResource(R.string.btn_birdnet)) }
                TextButton(
                    onClick = onPickPerch,
                    enabled = isPerchInstalled,
                ) { Text(stringResource(R.string.btn_perch)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
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
                stringResource(R.string.review_no_location)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun InferenceProgressBlock(progress: Float) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.review_analyzing_audio), style = MaterialTheme.typography.labelMedium)
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

    val alreadyUploadedForLabel = state.status == DraftStatus.UPLOADED || state.inatSubmission is InatSubmissionState.Done
    val label = when {
        state.inatSubmission is InatSubmissionState.InProgress -> stringResource(R.string.review_submit_uploading)
        alreadyUploadedForLabel -> stringResource(R.string.review_submit_already_uploaded)
        selectedCount == 0 -> stringResource(R.string.review_submit_select_species)
        else -> pluralStringResource(R.plurals.review_submit_selected, selectedCount, selectedCount)
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
    observations: List<InatObsEntry>,
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
                Icons.Filled.Eco,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                pluralStringResource(R.plurals.review_obs_uploaded, observations.size, observations.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        observations.forEach { (scientificName, _, url) ->
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
                    Text(stringResource(R.string.btn_view), style = MaterialTheme.typography.bodySmall)
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
                    contentDescription = if (isPlaying) stringResource(R.string.btn_pause) else stringResource(R.string.btn_play),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) stringResource(R.string.btn_pause) else stringResource(R.string.btn_play))
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
        RegionalStatus.CONFIRMED -> stringResource(R.string.cd_region_confirmed)
        RegionalStatus.NOT_CONFIRMED -> stringResource(R.string.cd_region_not_confirmed)
        RegionalStatus.UNVERIFIED -> stringResource(R.string.cd_region_unverified)
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
    onRowClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tintColor = if (row.regionalStatus == RegionalStatus.NOT_CONFIRMED)
        if (isDark) detectionCardUnlikelyDark else detectionCardUnlikelyLight
    else
        if (isDark) detectionCardLikelyDark else detectionCardLikelyLight
    val containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else tintColor
    val context = LocalContext.current
    ListItem(
        modifier = Modifier
            .clickable { onRowClick() }
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
                row.regionalStatus?.let { RegionalStatusIcon(it) }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    speciesRowTrailingLabel(row.maxConfidence, row.detectedWindows),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (uploadedUrl != null) {
                    Icon(
                        Icons.Filled.Eco,
                        contentDescription = stringResource(R.string.cd_uploaded_to_inat),
                        tint = INAT_GREEN,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Checkbox(
                        checked = row.isSelected,
                        onCheckedChange = onCheckedChange,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

internal fun speciesRowTrailingLabel(confidence: Float, detectedWindows: Int): String =
    "${(confidence * PERCENT).toInt()}% ×$detectedWindows"

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
private const val DENOISE_HELP_SIZE_DP = 24
private const val DENOISE_HELP_ICON_SIZE_DP = 18
private const val PHOTO_SIZE_DP = 48
private const val PHOTO_CORNER_DP = 8
private val INAT_GREEN = Color(0xFF74AC00)
