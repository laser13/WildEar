package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sound2inat.inat.PhotoVisionPlanner
import com.sound2inat.inat.PhotoVisionSuggestion
import com.sound2inat.inat.PhotoVisionTarget

@Composable
fun PhotoWorkflowActionsCard(
    state: PhotoReviewUiState,
    onAddMorePhotos: () -> Unit,
    onUpload: () -> Unit,
    onRunVision: () -> Unit,
    onOpenObservation: (String) -> Unit,
) {
    val primaryLabel = when {
        state.isUploading -> "Uploading..."
        state.vision.isLoading -> "Getting suggestions..."
        !state.isUploaded -> "Upload to iNaturalist"
        else -> "Get iNaturalist suggestions"
    }
    val primaryEnabled = when {
        state.isUploading || state.vision.isLoading -> false
        !state.isUploaded -> state.images.isNotEmpty()
        else -> state.canRunVision
    }
    val primaryAction = when {
        state.isUploading -> onUpload
        state.vision.isLoading -> onRunVision
        !state.isUploaded -> onUpload
        else -> onRunVision
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Workflow actions", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    !state.isUploaded -> "Upload the observation first, then request iNaturalist suggestions."
                    state.canRunVision -> "Get iNaturalist suggestions for the uploaded observation."
                    state.vision.isLoading -> "iNaturalist suggestions are loading."
                    else -> "The upload is ready. Request iNaturalist suggestions when the observation syncs."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = primaryAction,
                enabled = primaryEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                if (state.isUploading || state.vision.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .width(18.dp)
                            .height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(primaryLabel)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onAddMorePhotos,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Add photos")
                }
                state.observationUrl?.let { url ->
                    FilledTonalButton(
                        onClick = { onOpenObservation(url) },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Open on iNaturalist")
                    }
                }
            }
        }
    }
}

@Composable
fun INaturalistStatusCard(
    state: PhotoReviewUiState,
    onUpload: () -> Unit,
    onRetrySync: () -> Unit,
    onOpenObservation: (String) -> Unit,
) {
    val observationUrl = state.observationUrl

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = if (state.isUploaded) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("iNaturalist", style = MaterialTheme.typography.titleMedium)
            when {
                state.submitError != null && !state.isUploaded -> {
                    Text(
                        text = state.submitError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onUpload,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Upload to iNaturalist")
                    }
                }
                state.isUploading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(20.dp)
                                .height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Uploading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.isUploaded -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Uploaded",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.inatObservationId?.let { id ->
                                if (observationUrl != null) {
                                    TextButton(onClick = { onOpenObservation(observationUrl) }) {
                                        Text("Observation ID $id")
                                    }
                                } else {
                                    Text(
                                        text = "Observation ID $id",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (state.syncError != null) {
                        Text(
                            text = state.syncError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRetrySync) {
                            Text("Retry sync")
                        }
                    }
                    observationUrl?.let { url ->
                        TextButton(onClick = { onOpenObservation(url) }) {
                            Text("Open on iNaturalist")
                        }
                    }
                }
                else -> {
                    Text("Not uploaded yet", style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = onUpload,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Upload to iNaturalist")
                    }
                }
            }
        }
    }
}

@Composable
fun IdentificationStatusCard(
    state: PhotoReviewUiState,
) {
    val observedTaxonName = state.observationDetail?.taxonScientificName
    val observedTaxonCommonName = state.observationDetail?.taxonCommonName
    val localTaxonName = state.taxonScientificName
    val localTaxonCommonName = state.taxonCommonName
    val displayScientificName = observedTaxonName ?: localTaxonName
    val displayCommonName = observedTaxonCommonName ?: localTaxonCommonName

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (displayScientificName.isNullOrBlank()) {
                Text(
                    text = "No identification yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                displayCommonName?.takeIf { it.isNotBlank() }?.let { commonName ->
                    Text(commonName, style = MaterialTheme.typography.titleMedium)
                }
                Text(displayScientificName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun PhotoObservationSyncCard(
    state: PhotoReviewUiState,
    onRefresh: () -> Unit,
) {
    val hasNothingToShow = !state.isUploaded && state.observationDetail == null &&
        state.syncError == null && !state.isSyncingObservation
    if (hasNothingToShow) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("iNaturalist updates", style = MaterialTheme.typography.titleMedium)
            }

            when {
                state.isSyncingObservation -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Syncing observation details...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.syncError != null -> {
                    Text(
                        text = state.syncError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRefresh) {
                        Text("Try again")
                    }
                }
                state.observationDetail != null -> {
                    val detail = state.observationDetail
                    Text(
                        text = "${detail.qualityGrade} • ${detail.agreeingIdCount} agreeing IDs • ${detail.commentsCount} comments",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.comments.isNotEmpty()) {
                        detail.comments.take(2).forEach { comment ->
                            Text(
                                text = "${comment.username}: ${comment.body}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (detail.commentsCount > detail.comments.size) {
                            Text(
                                text = "More comments available on iNaturalist.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "No public comments yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Pull down to sync identifications and comments from iNaturalist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoVisionSuggestionsCard(
    state: PhotoReviewUiState,
    onApplySuggestion: (PhotoVisionSuggestion, String) -> Unit,
    onRetry: () -> Unit,
) {
    val photoVisionTargetSaver = Saver<PhotoVisionTarget, String>(
        save = { it.name },
        restore = { runCatching { PhotoVisionTarget.valueOf(it) }.getOrDefault(PhotoVisionTarget.SPECIES) },
    )
    var showAllSuggestions by rememberSaveable(state.draftId) { mutableStateOf(false) }
    var selectedTarget by rememberSaveable(state.draftId, stateSaver = photoVisionTargetSaver) {
        mutableStateOf(PhotoVisionTarget.SPECIES)
    }
    val ladder = state.vision.ladder
    val visibleSuggestions = ladder?.visibleSuggestions(showAllSuggestions).orEmpty()
    val selectedSuggestion = ladder?.let { PhotoVisionPlanner.chooseSuggestion(it, selectedTarget) }

    LaunchedEffect(ladder, selectedTarget) {
        if (ladder != null && PhotoVisionPlanner.chooseSuggestion(ladder, selectedTarget) == null) {
            selectedTarget = PhotoVisionTarget.SPECIES
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("iNaturalist suggestions", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhotoVisionTarget.entries.forEach { target ->
                    val enabled = ladder == null || PhotoVisionPlanner.chooseSuggestion(ladder, target) != null
                    FilterChip(
                        selected = selectedTarget == target,
                        onClick = { selectedTarget = target },
                        enabled = enabled,
                        label = { Text(target.selectionLabel()) },
                    )
                }
            }

            if (ladder == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "No suggestions loaded yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRetry,
                        enabled = state.canRunVision,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Get iNaturalist suggestions")
                    }
                }
            } else if (state.vision.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Getting suggestions...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (state.vision.error != null) {
                Text(
                    text = state.vision.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Try again")
                    }
                }
            } else if (!state.isUploaded) {
                Text("Upload first to get iNaturalist suggestions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (visibleSuggestions.isNotEmpty()) {
                state.vision.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visibleSuggestions.forEachIndexed { index, suggestion ->
                        ObservationSuggestionCard(
                            suggestion = suggestion,
                            isPrimary = index == 0,
                            onApply = {
                                if (selectedSuggestion != null) {
                                    onApplySuggestion(selectedSuggestion, selectedTarget.selectionLabel())
                                }
                            },
                        )
                    }
                    if (ladder.shouldShowAllToggle()) {
                        TextButton(onClick = { showAllSuggestions = !showAllSuggestions }) {
                            Text(if (showAllSuggestions) "Show fewer suggestions" else "Show all suggestions")
                        }
                    }
                }
            }
        }
    }
}

private fun PhotoVisionTarget.selectionLabel(): String = when (this) {
    PhotoVisionTarget.SPECIES -> "Species only"
    PhotoVisionTarget.GENUS -> "Genus only"
    PhotoVisionTarget.FAMILY -> "Family only"
}

@Composable
fun ObservationSuggestionCard(
    suggestion: PhotoVisionSuggestion,
    isPrimary: Boolean,
    onApply: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            suggestion.photoUrl?.let { photoUrl ->
                AsyncImage(
                    model = photoUrl,
                    contentDescription = suggestion.commonName ?: suggestion.scientificName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                )
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = {
                    Text(
                        text = suggestion.commonName ?: suggestion.scientificName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = suggestion.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPrimary) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = "${suggestion.rank} • ${"%.1f".format(suggestion.score)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPrimary) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                trailingContent = {
                    Button(
                        onClick = onApply,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPrimary) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isPrimary) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        ),
                    ) {
                        Text("Apply")
                    }
                },
            )
        }
    }
}
