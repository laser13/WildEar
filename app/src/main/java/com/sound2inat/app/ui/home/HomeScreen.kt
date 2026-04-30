package com.sound2inat.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshModelState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            if (state.drafts.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                val groups = remember(state.drafts) { groupDraftsByDate(state.drafts) }
                LazyColumn(
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
                            RecordingCard(d, vm = vm, onClick = { onOpenDraft(d.id) })
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingCard(
    summary: DraftSummary,
    vm: HomeViewModelHilt,
    onClick: () -> Unit,
) {
    val topLabel by remember(summary.id) { vm.observeTopLabel(summary.id) }.collectAsState(initial = null)
    val topSpecies by remember(summary.id) {
        vm.observeTopSpecies(summary.id)
    }.collectAsState(initial = emptyList())
    val inatCount by remember(summary.id) {
        vm.observeInatObservationCount(summary.id)
    }.collectAsState(initial = 0)

    val (icon, iconBg) = statusVisuals(summary.status)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(STATUS_ICON_SIZE_DP.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(STATUS_ICON_INNER_DP.dp),
                    )
                }
            },
            headlineContent = {
                Text(topLabel ?: statusHeadline(summary.status))
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (topSpecies.isNotEmpty()) {
                        SpeciesAvatarRow(items = topSpecies, vm = vm)
                    }
                    Text(
                        "${formatTimestamp(summary.recordedAtUtcMs)}  ·  ${formatDuration(summary.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = uploadBadge(summary, inatCount),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SpeciesAvatarRow(items: List<TopSpeciesItem>, vm: HomeViewModelHilt) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val photoUrl by vm.observeTaxonPhoto(item.scientificName).collectAsState()
            Box(
                modifier = Modifier
                    .size(SPECIES_AVATAR_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = item.commonName ?: item.scientificName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
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
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(BADGE_ICON_SIZE_DP.dp),
                )
                Text(
                    if (inatCount > 1) "Uploaded · $inatCount" else "Uploaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun statusVisuals(status: DraftStatus): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        DraftStatus.PENDING_INFERENCE -> Icons.Filled.Autorenew to colors.secondary
        DraftStatus.PENDING_REVIEW -> Icons.Filled.Visibility to colors.tertiary
        DraftStatus.REVIEWED -> Icons.Filled.Done to colors.primary
        DraftStatus.UPLOADED -> Icons.Filled.CloudDone to colors.primary
    }
}

private fun statusHeadline(status: DraftStatus): String = when (status) {
    DraftStatus.PENDING_INFERENCE -> "Analyzing…"
    DraftStatus.PENDING_REVIEW -> "Ready to review"
    DraftStatus.REVIEWED -> "Ready to submit"
    DraftStatus.UPLOADED -> "Submitted to iNaturalist"
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val STATUS_ICON_SIZE_DP = 40
private const val STATUS_ICON_INNER_DP = 20
private const val BADGE_ICON_SIZE_DP = 14
private const val SPECIES_AVATAR_SIZE_DP = 22
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
