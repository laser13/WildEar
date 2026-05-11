package com.sound2inat.app.ui.review

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.sound2inat.app.R
import com.sound2inat.app.ui.FILE_PROVIDER_AUTHORITY
import com.sound2inat.app.ui.formatDurationMs
import com.sound2inat.app.ui.theme.detectionCardLikelyDark
import com.sound2inat.app.ui.theme.detectionCardLikelyLight
import com.sound2inat.app.ui.theme.detectionCardUnlikelyDark
import com.sound2inat.app.ui.theme.detectionCardUnlikelyLight
import com.sound2inat.app.ui.theme.iNatGreen
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.DraftStatus
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { pageIndex ->
        val draftId = draftIds.getOrNull(pageIndex) ?: return@HorizontalPager
        val vm = remember(draftId) { pagerVm.factory.create(draftId, pagerVm.viewModelScope) }
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
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.audioPath) {
        if (state.audioPath != null) vm.ensureVisuals(filesDir)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { state.exportEffect }
            .collect { effect ->
                if (effect == null) return@collect
                vm.consumeExportEffect() // consume BEFORE the side effect fires
                when (effect) {
                    is ReviewExportEffect.ShareAudioFile -> {
                        try {
                            val uri: Uri = FileProvider.getUriForFile(
                                context,
                                FILE_PROVIDER_AUTHORITY,
                                effect.file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/wav"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, effect.file.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (_: Exception) {
                            snackbarHostState.showSnackbar("Could not share audio")
                        }
                    }
                    is ReviewExportEffect.ShowSnackbar -> {
                        snackbarHostState.showSnackbar(effect.message)
                    }
                }
            }
    }

    var pickerVisible by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var detailsRow by remember { mutableStateOf<SpeciesRow?>(null) }
    val isAnalysisRunning = state.inferenceProgress != null ||
        state.perchProgress != null ||
        state.queuePosition != null
    val uploadedUrls = remember(
        state.inatObservations
    ) { state.inatObservations.associate { it.scientificName to it.url } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_review)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete))
                    }
                },
            )
        },
        bottomBar = { SubmitBottomBar(state = state, vm = vm) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        onSeek = vm::seekTo,
                    )
                }
                item {
                    HabitatPhotoStrip(
                        draftId = state.draftId,
                        photos = state.habitatPhotos,
                        vm = vm,
                    )
                }
                state.queuePosition?.let { position ->
                    if (state.inferenceProgress == null) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (position == 0) {
                                        stringResource(R.string.inference_queued_next)
                                    } else {
                                        stringResource(
                                            R.string.inference_queued_position,
                                            position,
                                            (state.estimatedWaitMs ?: 0L) / 60_000L,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(2.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
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
                        hasHabitatPhotos = state.habitatPhotos.isNotEmpty(),
                        onToggleHabitatPhoto = { vm.toggleHabitatPhoto(row.detectionId) },
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
                            hasHabitatPhotos = state.habitatPhotos.isNotEmpty(),
                            onToggleHabitatPhoto = { vm.toggleHabitatPhoto(row.detectionId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_delete_single_title)) },
            text = { Text(stringResource(R.string.dialog_delete_single_body)) },
            confirmButton = {
                TextButton(
                    onClick = { vm.delete(onDeleted = onBack) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    if (pickerVisible) {
        ModelPickerDialog(
            isPerchInstalled = state.isPerchInstalled,
            onConfirm = { runBirdnet, runPerch ->
                pickerVisible = false
                vm.reanalyze(runBirdnet, runPerch)
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
            exportingAction = state.exportingAction,
            onShareClip = { vm.onShareSpeciesClip(liveRow) },
            onSaveClip = { vm.onSaveSpeciesClip(liveRow) },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ModelPickerDialog(
    isPerchInstalled: Boolean,
    onConfirm: (runBirdnet: Boolean, runPerch: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var birdnetChecked by remember { mutableStateOf(true) }
    var perchChecked by remember(isPerchInstalled) { mutableStateOf(isPerchInstalled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_reanalyze_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.dialog_reanalyze_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = birdnetChecked,
                            onValueChange = { birdnetChecked = it },
                            role = Role.Checkbox,
                        ),
                ) {
                    Checkbox(
                        checked = birdnetChecked,
                        onCheckedChange = null,
                    )
                    Text(stringResource(R.string.btn_birdnet))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = perchChecked,
                            enabled = isPerchInstalled,
                            onValueChange = { perchChecked = it },
                            role = Role.Checkbox,
                        ),
                ) {
                    Checkbox(
                        checked = perchChecked,
                        onCheckedChange = null,
                        enabled = isPerchInstalled,
                    )
                    Text(stringResource(R.string.btn_perch))
                }
                if (!isPerchInstalled) {
                    Text(
                        stringResource(R.string.dialog_perch_not_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(birdnetChecked, perchChecked) },
                enabled = birdnetChecked || perchChecked,
            ) {
                Text(stringResource(R.string.btn_ok))
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
                    contentDescription = if (isPlaying) {
                        stringResource(
                            R.string.btn_pause
                        )
                    } else {
                        stringResource(R.string.btn_play)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) stringResource(R.string.btn_pause) else stringResource(R.string.btn_play))
            }
            Text("${formatDurationMs(positionMs)} / ${formatDurationMs(state.durationMs)}")
            val isExporting = state.exportingAction != null
            IconButton(
                onClick = { vm.onShareFullRecording() },
                enabled = !isExporting,
            ) {
                if (state.exportingAction is ExportingAction.FullRecordingShare) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.cd_share_recording))
                }
            }
            IconButton(
                onClick = { vm.onSaveFullRecording() },
                enabled = !isExporting,
            ) {
                if (state.exportingAction is ExportingAction.FullRecordingSave) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.cd_save_recording))
                }
            }
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
    hasHabitatPhotos: Boolean = false,
    onToggleHabitatPhoto: () -> Unit = {},
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tintColor = if (row.regionalStatus == RegionalStatus.NOT_CONFIRMED) {
        if (isDark) detectionCardUnlikelyDark else detectionCardUnlikelyLight
    } else {
        if (isDark) detectionCardLikelyDark else detectionCardLikelyLight
    }
    val containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else tintColor
    val context = LocalContext.current
    ListItem(
        modifier = Modifier
            .clickable { onRowClick() }
            .background(containerColor),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (uploadedUrl == null) {
                    Checkbox(
                        checked = row.isSelected,
                        onCheckedChange = onCheckedChange,
                    )
                }
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
                        tint = iNatGreen,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (hasHabitatPhotos) {
                    IconButton(onClick = onToggleHabitatPhoto) {
                        Icon(
                            imageVector = if (row.includeHabitatPhoto) {
                                Icons.Filled.CameraAlt
                            } else {
                                Icons.Outlined.CameraAlt
                            },
                            contentDescription = stringResource(R.string.label_include_habitat_photo),
                            tint = if (row.includeHabitatPhoto) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun HabitatPhotoStrip(
    draftId: String,
    photos: List<DraftPhotoEntity>,
    vm: ReviewViewModel,
) {
    val context = LocalContext.current
    var pendingPhotoId by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val id = pendingPhotoId
        val path = pendingPhotoPath
        if (success && id != null && path != null) {
            vm.onPhotoTaken(draftId, id, path)
        }
        pendingPhotoId = null
        pendingUri = null
        pendingPhotoPath = null
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Add photo" button is always shown first
        item {
            Box(
                modifier = Modifier
                    .size(HABITAT_PHOTO_SIZE_DP.dp)
                    .clip(RoundedCornerShape(PHOTO_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        val newId = UUID.randomUUID().toString()
                        val (uri, path) = vm.preparePhotoCaptureWithPath(context, draftId, newId)
                        pendingPhotoId = newId
                        pendingUri = uri
                        pendingPhotoPath = path
                        launcher.launch(uri)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AddAPhoto,
                    contentDescription = stringResource(R.string.cd_take_habitat_photo),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        // Existing photos
        items(photos, key = { it.id }) { photo ->
            Box(
                modifier = Modifier
                    .size(HABITAT_PHOTO_SIZE_DP.dp)
                    .clip(RoundedCornerShape(PHOTO_CORNER_DP.dp)),
            ) {
                AsyncImage(
                    model = File(photo.photoPath),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Delete button overlay
                IconButton(
                    onClick = { vm.onPhotoDeleted(photo.id, photo.photoPath) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_delete),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
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

private const val PERCENT = 100f
private const val DENOISE_HELP_SIZE_DP = 24
private const val DENOISE_HELP_ICON_SIZE_DP = 18
private const val PHOTO_SIZE_DP = 48
private const val PHOTO_CORNER_DP = 8
private const val HABITAT_PHOTO_SIZE_DP = 80
private const val OVERLAY_ALPHA = 0.7f
