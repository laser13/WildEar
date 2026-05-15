package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.storage.PhotoDraftSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Suppress("FunctionNaming")
@Composable
fun PhotosScreen(
    onOpenPhotoDraft: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    val vm: PhotosViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                Text("Loading photos...", modifier = Modifier.align(Alignment.Center))
            }
            state.drafts.isEmpty() -> {
                EmptyPhotosState(
                    onStartCapture = onStartCapture,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                val groups = remember(state.drafts) { groupDraftsByDate(state.drafts) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        Text("Observations", style = MaterialTheme.typography.headlineMedium)
                    }
                    groups.forEach { group ->
                        item(key = "photos_header_${group.label}") {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(group.drafts, key = { it.id }) { draft ->
                            PhotoDraftCard(
                                draft = draft,
                                onClick = { onOpenPhotoDraft(draft.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EmptyPhotosState(
    onStartCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Observations", style = MaterialTheme.typography.headlineMedium)
        Text("Photo observations will appear here.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onStartCapture) {
            Text("Take photos")
        }
    }
}

private data class DateGroup(val label: String, val drafts: List<PhotoDraftSummary>)

private fun groupDraftsByDate(
    drafts: List<PhotoDraftSummary>,
    today: LocalDate = LocalDate.now(),
): List<DateGroup> {
    if (drafts.isEmpty()) return emptyList()
    val yesterday = today.minusDays(1)
    val weekAgo = today.minusDays(WEEK_DAYS.toLong())
    val zone = ZoneId.systemDefault()
    val recentFmt = DateTimeFormatter.ofPattern("EEEE, MMM d").withZone(zone)
    val olderFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(zone)
    val groups = LinkedHashMap<String, MutableList<PhotoDraftSummary>>()
    // drafts arrive pre-sorted by observedAtUtcMs DESC from the DAO
    for (draft in drafts) {
        val draftDate = LocalDate.ofEpochDay(draft.observedAtUtcMs / DAY_MS)
        val label = when {
            draftDate >= today -> "Today"
            draftDate >= yesterday -> "Yesterday"
            draftDate > weekAgo -> recentFmt.format(Instant.ofEpochMilli(draft.observedAtUtcMs))
            else -> olderFmt.format(Instant.ofEpochMilli(draft.observedAtUtcMs))
        }
        groups.getOrPut(label) { mutableListOf() }.add(draft)
    }
    return groups.map { (label, ds) -> DateGroup(label, ds) }
}

private const val DAY_MS = 86_400_000L
private const val WEEK_DAYS = 7
