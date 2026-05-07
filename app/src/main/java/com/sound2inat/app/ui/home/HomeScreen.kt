package com.sound2inat.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.sound2inat.storage.DraftStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onOpenDraft: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModelHilt = hiltViewModel()
    val state by vm.delegate.state.collectAsState()
    val filterMode by vm.filterMode.collectAsState()
    val enrichedDrafts by vm.enrichedDrafts.collectAsState()
    val filteredDrafts by vm.filteredDrafts.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val allowDeleteUploaded by vm.allowDeleteUploaded.collectAsState()
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
            TopAppBar(
                title = { Text("WildEar") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                text = { Text("Record") },
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
                        "Model not installed — analysis will run after you install it in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (enrichedDrafts.isNotEmpty()) {
                RecordingFilterBar(
                    filterMode = filterMode,
                    onFilterChange = { vm.setFilterMode(it) },
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
                        EmptyState(modifier = Modifier.fillMaxSize())
                    }
                    filteredDrafts.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No recordings match this filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        val groups = remember(filteredDrafts) { groupDraftsByDate(filteredDrafts) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groups.forEach { group ->
                                item(key = "header_${group.label}") {
                                    Text(
                                        group.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(group.drafts, key = { it.id }) { d ->
                                    RecordingCard(
                                        summary = d,
                                        vm = vm,
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
}

@Suppress("FunctionNaming")
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.MicNone,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No recordings yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Tap Record to capture wildlife sounds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    summary: DraftSummary,
    vm: HomeViewModelHilt,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val topLabel by remember(summary.id) { vm.observeTopLabel(summary.id) }.collectAsState(initial = null)
    val topSpecies by remember(summary.id) {
        vm.observeTopSpecies(summary.id)
    }.collectAsState(initial = emptyList())
    val detectionCount by remember(summary.id) {
        vm.observeDetectionCount(summary.id)
    }.collectAsState(initial = 0)
    val inatCount by remember(summary.id) {
        vm.observeInatObservationCount(summary.id)
    }.collectAsState(initial = 0)

    val analysedButEmpty = topSpecies.isEmpty() &&
        (
            summary.status == DraftStatus.PENDING_REVIEW ||
                summary.status == DraftStatus.REVIEWED
            )
    val (icon, iconBg) = statusVisuals(summary.status, analysedButEmpty)
    val badge = uploadBadge(summary, inatCount)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
            ),
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                    }
                    RecordingThumbnail(
                        firstSpecies = topSpecies.firstOrNull(),
                        icon = icon,
                        iconBg = iconBg,
                        vm = vm,
                    )
                }
            },
            headlineContent = {
                Text(topLabel ?: statusHeadline(summary.status, analysedButEmpty))
            },
            supportingContent = {
                Text(
                    "${formatTimestamp(summary.recordedAtUtcMs)} · " +
                        homeStatusLabel(summary.status, analysedButEmpty),
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
                        formatDuration(summary.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detectionCount > 0) {
                        val countText = if (inatCount > 0) "$inatCount/$detectionCount" else "$detectionCount"
                        Text(
                            countText,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (inatCount > 0) INAT_GREEN
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    badge?.invoke()
                }
            },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RecordingThumbnail(
    firstSpecies: TopSpeciesItem?,
    icon: ImageVector,
    iconBg: Color,
    vm: HomeViewModelHilt,
) {
    val shape = RoundedCornerShape(RECORDING_THUMB_CORNER_DP.dp)
    var photoUrl: String? = null
    if (firstSpecies != null) {
        val observedUrl by remember(firstSpecies.scientificName) {
            vm.observeTaxonPhoto(firstSpecies.scientificName)
        }.collectAsState()
        photoUrl = observedUrl
    }
    Box(
        modifier = Modifier
            .size(RECORDING_THUMB_SIZE_DP.dp)
            .clip(shape)
            .background(iconBg),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = firstSpecies?.commonName ?: firstSpecies?.scientificName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(STATUS_ICON_INNER_DP.dp),
            )
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
            contentDescription = "Uploaded to iNaturalist",
            tint = INAT_GREEN,
            modifier = Modifier.size(BADGE_ICON_SIZE_DP.dp),
        )
    }
}

@Composable
private fun statusVisuals(
    status: DraftStatus,
    analysedButEmpty: Boolean,
): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    if (analysedButEmpty) return Icons.Filled.SearchOff to colors.outline
    return when (status) {
        DraftStatus.PENDING_INFERENCE -> Icons.Filled.Autorenew to colors.secondary
        DraftStatus.PENDING_REVIEW -> Icons.Filled.Visibility to colors.tertiary
        DraftStatus.REVIEWED -> Icons.Filled.Done to colors.primary
        DraftStatus.UPLOADED -> Icons.Filled.CloudDone to colors.primary
    }
}

private fun statusHeadline(status: DraftStatus, analysedButEmpty: Boolean): String {
    if (analysedButEmpty) return "Nothing detected"
    return when (status) {
        DraftStatus.PENDING_INFERENCE -> "Analyzing…"
        DraftStatus.PENDING_REVIEW -> "Ready to review"
        DraftStatus.REVIEWED -> "Ready to submit"
        DraftStatus.UPLOADED -> "Submitted to iNaturalist"
    }
}

internal fun homeStatusLabel(status: DraftStatus, analysedButEmpty: Boolean): String {
    if (analysedButEmpty) return "No detections"
    return when (status) {
        DraftStatus.PENDING_INFERENCE -> "Analyzing"
        DraftStatus.PENDING_REVIEW -> "Needs review"
        DraftStatus.REVIEWED -> "Not submitted"
        DraftStatus.UPLOADED -> "Submitted"
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private val INAT_GREEN = Color(0xFF74AC00)
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val RECORDING_THUMB_SIZE_DP = 48
private const val RECORDING_THUMB_CORNER_DP = 6
private const val STATUS_ICON_INNER_DP = 20
private const val BADGE_ICON_SIZE_DP = 14
private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val WEEK_DAYS = 7

private data class DateGroup(val label: String, val drafts: List<DraftSummary>)

/**
 * Groups recording drafts by the local calendar day they were recorded on.
 * Today / Yesterday get human labels; days within a week show weekday + date;
 * older entries fall back to a full date stamp. Order: newest-first within
 * each group, groups themselves newest-first.
 */
private fun groupDraftsByDate(
    drafts: List<DraftSummary>,
    now: Long = System.currentTimeMillis(),
): List<DateGroup> {
    if (drafts.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val todayStart = startOfDay(now, cal)
    val yesterdayStart = todayStart - DAY_MS
    val recentFmt = SimpleDateFormat("EEEE, MMM d", Locale.US)
    val olderFmt = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    val groups = LinkedHashMap<String, MutableList<DraftSummary>>()
    for (d in drafts.sortedByDescending { it.recordedAtUtcMs }) {
        val dayStart = startOfDay(d.recordedAtUtcMs, cal)
        val label = when {
            dayStart >= todayStart -> "Today"
            dayStart >= yesterdayStart -> "Yesterday"
            (todayStart - dayStart) < WEEK_DAYS * DAY_MS -> recentFmt.format(Date(d.recordedAtUtcMs))
            else -> olderFmt.format(Date(d.recordedAtUtcMs))
        }
        groups.getOrPut(label) { mutableListOf() }.add(d)
    }
    return groups.map { (label, ds) -> DateGroup(label, ds) }
}

private fun startOfDay(ms: Long, cal: Calendar): Long {
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingFilterBar(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    selectedCount: Int,
    totalVisible: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val allSelected = filterMode == FilterMode.NOTHING_DETECTED &&
        selectedCount > 0 && selectedCount == totalVisible
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filterMode == FilterMode.UPLOADED,
                onClick = {
                    onFilterChange(if (filterMode == FilterMode.UPLOADED) FilterMode.ALL else FilterMode.UPLOADED)
                },
                label = {
                    Icon(Icons.Filled.Eco, contentDescription = "Uploaded", modifier = Modifier.size(18.dp))
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
                    Icon(Icons.Filled.SearchOff, contentDescription = "Nothing detected", modifier = Modifier.size(18.dp))
                },
            )
        }
        if (filterMode == FilterMode.NOTHING_DETECTED && totalVisible > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
                    Text(if (allSelected) "Deselect all" else "Select all")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selectedCount > 0) {
                    TextButton(
                        onClick = onDeleteSelected,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete ($selectedCount)")
                    }
                }
            }
        }
    }
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
        title = { Text("Delete ${preview.toDelete.size} recording${if (preview.toDelete.size > 1) "s" else ""}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This will permanently delete the selected recordings and their audio files.")
                if (preview.skippedUploaded > 0) {
                    Text(
                        "${preview.skippedUploaded} recording${if (preview.skippedUploaded > 1) "s" else ""} " +
                            "with iNaturalist observations will be kept. " +
                            "You can allow deleting them in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text(if (blocked) "Cannot delete" else "Delete recording?") },
        text = {
            Text(
                if (blocked)
                    "This recording has an iNaturalist observation. To allow deleting it, enable \"Allow deleting recordings with observations\" in Settings."
                else
                    "This will permanently delete the recording and its audio file.",
            )
        },
        confirmButton = {
            if (!blocked) {
                TextButton(
                    onClick = onConfirm,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (blocked) "OK" else "Cancel") }
        },
    )
}
