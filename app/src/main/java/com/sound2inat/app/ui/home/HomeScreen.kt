package com.sound2inat.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sound2inat.app.R
import com.sound2inat.app.inference.JobStatus
import com.sound2inat.app.ui.common.EmptyState
import com.sound2inat.app.ui.common.Sound2iNatTopBar
import com.sound2inat.app.ui.common.datedSections
import com.sound2inat.app.ui.common.groupDatedItems
import com.sound2inat.app.ui.formatDurationMs
import com.sound2inat.app.ui.theme.iNatGreen
import com.sound2inat.app.ui.theme.onScrimLight
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.storage.DraftStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenDraft: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val filterMode by vm.filterMode.collectAsStateWithLifecycle()
    val enrichedDrafts by vm.enrichedDrafts.collectAsStateWithLifecycle()
    val filteredDrafts by vm.filteredDrafts.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val allowDeleteUploaded by vm.allowDeleteUploaded.collectAsStateWithLifecycle()
    val selectionMode = filterMode == FilterMode.NOTHING_DETECTED
    var bulkDeletePreview by remember { mutableStateOf<BulkDeletePreview?>(null) }
    var longPressedDraft by remember { mutableStateOf<DraftSummary?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshModelState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    bulkDeletePreview?.let { preview ->
        BulkDeleteDialog(
            preview = preview,
            allowDeleteUploaded = allowDeleteUploaded,
            onConfirm = {
                vm.bulkDelete(preview.toDelete.map { it.id })
                bulkDeletePreview = null
                vm.setFilterMode(FilterMode.ALL)
            },
            onDismiss = { bulkDeletePreview = null },
        )
    }

    longPressedDraft?.let { draft ->
        val isUploaded = draft.inatCount > 0 || draft.status == DraftStatus.UPLOADED
        val blocked = isUploaded && !allowDeleteUploaded
        SingleDeleteDialog(
            blocked = blocked,
            onConfirm = {
                if (!blocked) vm.bulkDelete(listOf(draft.id))
                longPressedDraft = null
            },
            onDismiss = { longPressedDraft = null },
        )
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                filterMode = filterMode,
                onFilterChange = { vm.setFilterMode(it) },
                onSettings = onSettings,
                showFilterChips = enrichedDrafts.isNotEmpty(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.isModelReady) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.home_model_not_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (filterMode == FilterMode.NOTHING_DETECTED && filteredDrafts.isNotEmpty()) {
                BulkActionsRow(
                    selectedCount = selectedIds.size,
                    totalVisible = filteredDrafts.size,
                    onSelectAll = { vm.selectAllVisible() },
                    onClearSelection = { vm.clearSelection() },
                    onDeleteSelected = { bulkDeletePreview = vm.previewSelectedDelete() },
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    enrichedDrafts.isEmpty() -> {
                        EmptyState(
                            modifier = Modifier.fillMaxSize(),
                            icon = Icons.Outlined.MicNone,
                            title = stringResource(R.string.home_empty_title),
                            detail = stringResource(R.string.home_empty_subtitle),
                        )
                    }
                    filteredDrafts.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.home_no_recordings_filter),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        val groups = remember(filteredDrafts) {
                            groupDatedItems(filteredDrafts) { it.recordedAtUtcMs }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 0.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            datedSections(
                                groups = groups,
                                itemKey = { it.id },
                            ) { d ->
                                RecordingCard(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    summary = d,
                                    observeRecordingSpecies = vm::observeRecordingSpecies,
                                    observeRecordingModels = vm::observeRecordingModels,
                                    observeDetectionCount = vm::observeDetectionCount,
                                    observeInatObservationCount = vm::observeInatObservationCount,
                                    observeTaxonPhoto = vm::observeTaxonPhoto,
                                    selectionMode = selectionMode,
                                    selected = d.id in selectedIds,
                                    onToggleSelection = { vm.toggleSelection(d.id) },
                                    onClick = { onOpenDraft(d.id) },
                                    onLongClick = { longPressedDraft = d },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    modifier: Modifier = Modifier,
    summary: DraftSummary,
    observeRecordingSpecies: (String) -> Flow<List<RecordingSpeciesItem>>,
    observeRecordingModels: (String) -> Flow<Set<ModelBadge>>,
    observeDetectionCount: (String) -> Flow<Int>,
    observeInatObservationCount: (String) -> Flow<Int>,
    observeTaxonPhoto: (String) -> Flow<String?>,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val species by remember(summary.id) {
        observeRecordingSpecies(summary.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val models by remember(summary.id) {
        observeRecordingModels(summary.id)
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val detectionCount by remember(summary.id) {
        observeDetectionCount(summary.id)
    }.collectAsStateWithLifecycle(initialValue = 0)
    val inatCount by remember(summary.id) {
        observeInatObservationCount(summary.id)
    }.collectAsStateWithLifecycle(initialValue = 0)

    val analysedButEmpty = species.isEmpty() &&
        (
            summary.status == DraftStatus.PENDING_REVIEW ||
                summary.status == DraftStatus.REVIEWED
            )
    val (icon, iconBg) = statusVisuals(summary.status, analysedButEmpty, summary.jobStatus)
    val badge = uploadBadge(summary, inatCount)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        if (species.isNotEmpty()) {
            // Photos pinned to the top of the card; capture time, audio duration and
            // detection count moved to a single bottom row. The recording date now
            // lives in the section header, so only the time is shown here.
            SpeciesPhotoStrip(species = species, observeTaxonPhoto = observeTaxonPhoto)
            if (models.isNotEmpty()) {
                ModelBadgesRow(models = models)
            }
            // Re-analysis: the strip stays populated while a fresh job runs, so
            // surface its live progress here (the empty branch can't, having a strip).
            activeJobLabel(summary.status, summary.jobStatus)?.let { jobLabel ->
                Text(
                    jobLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                }
                Text(
                    "${formatTime(summary.recordedAtUtcMs)} · ${formatDurationMs(summary.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (detectionCount > 0) {
                    val countText = if (inatCount > 0) "$inatCount/$detectionCount" else "$detectionCount"
                    Text(
                        countText,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (inatCount > 0) iNatGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }
                badge?.invoke()
            }
        } else {
            // Fallback: status icon + headline (analysing / empty / failed / queued)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (selectionMode) {
                            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                        }
                        RecordingThumbnail(icon = icon, iconBg = iconBg)
                    }
                },
                headlineContent = {
                    Text(statusHeadline(summary.status, analysedButEmpty, summary.jobStatus))
                },
                supportingContent = {
                    Text(
                        formatTime(summary.recordedAtUtcMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            formatDurationMs(summary.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detectionCount > 0) {
                            val countText = if (inatCount > 0) "$inatCount/$detectionCount" else "$detectionCount"
                            Text(
                                countText,
                                style = MaterialTheme.typography.titleLarge,
                                color = if (inatCount > 0) iNatGreen else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        badge?.invoke()
                    }
                },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RecordingThumbnail(
    icon: ImageVector,
    iconBg: Color,
) {
    val shape = RoundedCornerShape(RECORDING_THUMB_CORNER_DP.dp)
    Box(
        modifier = Modifier
            .size(RECORDING_THUMB_SIZE_DP.dp)
            .clip(shape)
            .background(iconBg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = onScrimLight,
            modifier = Modifier.size(STATUS_ICON_INNER_DP.dp),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SpeciesPhotoStrip(
    species: List<RecordingSpeciesItem>,
    observeTaxonPhoto: (String) -> Flow<String?>,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(species, key = { it.scientificName }) { item ->
            SpeciesPhotoTile(item = item, observeTaxonPhoto = observeTaxonPhoto)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SpeciesPhotoTile(
    item: RecordingSpeciesItem,
    observeTaxonPhoto: (String) -> Flow<String?>,
) {
    val shape = RoundedCornerShape(RECORDING_THUMB_CORNER_DP.dp)
    val photoUrl by remember(item.scientificName) {
        observeTaxonPhoto(item.scientificName)
    }.collectAsStateWithLifecycle(initialValue = null)
    val dimmed = item.regionalStatus == RegionalStatus.NOT_CONFIRMED
    Box(
        modifier = Modifier
            .size(RECORDING_STRIP_THUMB_SIZE_DP.dp)
            .alpha(if (dimmed) NOT_CONFIRMED_ALPHA else 1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = item.commonName ?: item.scientificName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Icon(
                Icons.Filled.Eco,
                contentDescription = item.commonName ?: item.scientificName,
                tint = onScrimLight,
                modifier = Modifier.size(STATUS_ICON_INNER_DP.dp),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ModelBadgesRow(models: Set<ModelBadge>) {
    if (models.isEmpty()) return
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        models.sortedBy { it.ordinal }.forEach { badge ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(RECORDING_THUMB_CORNER_DP.dp),
            ) {
                Text(
                    text = when (badge) {
                        ModelBadge.BIRDNET -> "BirdNET"
                        ModelBadge.PERCH -> "Perch"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Returns a composable that renders the "Uploaded" chip on the right side of
 * a recording card, or null when the draft has nothing to brag about. A chip
 * is shown when the draft has at least one persisted iNaturalist observation
 * OR its status is UPLOADED (covers the brief gap between submit success and
 * the per-draft observation rows landing in Room).
 */
@Composable
private fun uploadBadge(summary: DraftSummary, inatCount: Int): (@Composable () -> Unit)? {
    val uploaded = inatCount > 0 || summary.status == DraftStatus.UPLOADED
    if (!uploaded) return null
    return {
        Icon(
            Icons.Filled.Eco,
            contentDescription = stringResource(R.string.cd_uploaded_to_inat),
            tint = iNatGreen,
            modifier = Modifier.size(BADGE_ICON_SIZE_DP.dp),
        )
    }
}

@Composable
private fun statusVisuals(
    status: DraftStatus,
    analysedButEmpty: Boolean,
    jobStatus: JobStatus? = null,
): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    if (analysedButEmpty) return Icons.Filled.SearchOff to colors.outline
    if (status == DraftStatus.PENDING_INFERENCE || jobStatus != null) {
        return when (jobStatus) {
            is JobStatus.Failed -> Icons.Filled.Error to colors.error
            is JobStatus.Queued -> Icons.Filled.Schedule to colors.outline
            is JobStatus.Running, null -> Icons.Filled.Autorenew to colors.secondary
        }
    }
    return when (status) {
        DraftStatus.PENDING_INFERENCE -> Icons.Filled.Autorenew to colors.secondary
        DraftStatus.PENDING_REVIEW -> Icons.Filled.Visibility to colors.tertiary
        DraftStatus.REVIEWED -> Icons.Filled.Done to colors.primary
        DraftStatus.UPLOADED -> Icons.Filled.CloudDone to colors.primary
    }
}

/**
 * Live inference status text for a draft with an active analysis job:
 * "Analyzing… NN%" (with live percentage when a model reports it), the queue
 * position ("Up next" / "In queue (#3)"), or "Analysis failed". Returns null
 * when no job is active (no analysis in flight), so callers can fall back to a
 * workflow label or hide the line entirely.
 *
 * Shared by both card layouts so analysis progress shows whether or not the
 * recording already has detections — i.e. on a first analysis (empty strip)
 * AND on a re-analysis (photo strip still populated).
 */
@Composable
private fun activeJobLabel(status: DraftStatus, jobStatus: JobStatus?): String? {
    val analyzingRes = if (status == DraftStatus.PENDING_INFERENCE) {
        R.string.home_headline_analyzing
    } else {
        R.string.home_headline_reanalyzing
    }
    return when (jobStatus) {
        is JobStatus.Failed -> stringResource(R.string.home_headline_analysis_failed)
        is JobStatus.Queued ->
            if (jobStatus.position == 0) {
                stringResource(R.string.home_label_up_next)
            } else {
                stringResource(R.string.home_label_in_queue, jobStatus.position + 1)
            }
        is JobStatus.Running -> {
            val pct = (jobStatus.birdnetProgress ?: jobStatus.perchProgress)
                ?.let { (it * 100).toInt() }
            if (pct != null) {
                stringResource(
                    R.string.home_label_analyzing_progress,
                    pct
                )
            } else {
                stringResource(analyzingRes)
            }
        }
        // No queue entry yet, but the draft is still pending its first analysis.
        null -> if (status == DraftStatus.PENDING_INFERENCE) stringResource(analyzingRes) else null
    }
}

@Composable
private fun statusHeadline(status: DraftStatus, analysedButEmpty: Boolean, jobStatus: JobStatus? = null): String {
    if (analysedButEmpty) return stringResource(R.string.home_headline_nothing_detected)
    activeJobLabel(status, jobStatus)?.let { return it }
    return stringResource(
        when (status) {
            DraftStatus.PENDING_INFERENCE -> R.string.home_headline_analyzing
            DraftStatus.PENDING_REVIEW -> R.string.home_headline_ready_review
            DraftStatus.REVIEWED -> R.string.home_headline_ready_submit
            DraftStatus.UPLOADED -> R.string.home_headline_submitted
        }
    )
}

private fun formatTime(ms: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(ms))
}

private const val RECORDING_THUMB_SIZE_DP = 48
private const val RECORDING_THUMB_CORNER_DP = 6
private const val STATUS_ICON_INNER_DP = 20
private const val BADGE_ICON_SIZE_DP = 14
private const val RECORDING_STRIP_THUMB_SIZE_DP = 72
private const val NOT_CONFIRMED_ALPHA = 0.45f

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filterMode == FilterMode.UPLOADED,
            onClick = {
                onFilterChange(if (filterMode == FilterMode.UPLOADED) FilterMode.ALL else FilterMode.UPLOADED)
            },
            label = {
                Icon(
                    Icons.Filled.Eco,
                    contentDescription = stringResource(R.string.cd_filter_uploaded),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        FilterChip(
            selected = filterMode == FilterMode.NOTHING_DETECTED,
            onClick = {
                onFilterChange(
                    if (filterMode == FilterMode.NOTHING_DETECTED) FilterMode.ALL else FilterMode.NOTHING_DETECTED,
                )
            },
            label = {
                Icon(
                    Icons.Filled.SearchOff,
                    contentDescription = stringResource(R.string.cd_filter_nothing_detected),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BulkActionsRow(
    selectedCount: Int,
    totalVisible: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val allSelected = selectedCount > 0 && selectedCount == totalVisible
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
            Text(
                if (allSelected) {
                    stringResource(R.string.filter_deselect_all)
                } else {
                    stringResource(R.string.filter_select_all)
                },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (selectedCount > 0) {
            TextButton(
                onClick = onDeleteSelected,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.filter_delete_selected, selectedCount))
            }
        }
    }
}

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    onSettings: () -> Unit,
    showFilterChips: Boolean,
) {
    Sound2iNatTopBar(
        title = stringResource(R.string.title_home),
        showLogo = true,
        inlineContent = if (showFilterChips) {
            {
                FilterChipsRow(
                    filterMode = filterMode,
                    onFilterChange = onFilterChange,
                )
            }
        } else {
            null
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                )
            }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun BulkDeleteDialog(
    preview: BulkDeletePreview,
    allowDeleteUploaded: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                pluralStringResource(R.plurals.dialog_bulk_delete_title, preview.toDelete.size, preview.toDelete.size)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialog_bulk_delete_body))
                if (preview.skippedUploaded > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.dialog_bulk_delete_skipped,
                            preview.skippedUploaded,
                            preview.skippedUploaded
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.btn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun SingleDeleteDialog(
    blocked: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (blocked) {
                    stringResource(
                        R.string.dialog_delete_single_blocked_title
                    )
                } else {
                    stringResource(R.string.dialog_delete_single_title)
                }
            )
        },
        text = {
            Text(
                if (blocked) {
                    stringResource(R.string.dialog_delete_single_blocked_body)
                } else {
                    stringResource(R.string.dialog_delete_single_body)
                },
            )
        },
        confirmButton = {
            if (!blocked) {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.btn_delete)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) { Text(if (blocked) stringResource(R.string.btn_ok) else stringResource(R.string.btn_cancel)) }
        },
    )
}
