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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sound2inat.app.R
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
        state.isUploading -> stringResource(R.string.photos_workflow_primary_uploading)
        state.vision.isLoading -> stringResource(R.string.photos_workflow_primary_getting)
        !state.isUploaded -> stringResource(R.string.photos_workflow_primary_upload)
        else -> stringResource(R.string.photos_workflow_primary_get_suggestions)
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
            Text(stringResource(R.string.photos_workflow_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    !state.isUploaded -> stringResource(R.string.photos_workflow_body_upload_first)
                    state.canRunVision -> stringResource(R.string.photos_workflow_body_can_run)
                    state.vision.isLoading -> stringResource(R.string.photos_workflow_body_loading)
                    else -> stringResource(R.string.photos_workflow_body_ready)
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
                    Text(stringResource(R.string.photos_workflow_add_photos))
                }
                state.observationUrl?.let { url ->
                    FilledTonalButton(
                        onClick = { onOpenObservation(url) },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.photos_workflow_open_inat))
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
            Text(stringResource(R.string.photos_inat_title), style = MaterialTheme.typography.titleMedium)
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
                        Text(stringResource(R.string.photos_workflow_primary_upload))
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
                            text = stringResource(R.string.photos_inat_uploading),
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
                                text = stringResource(R.string.photos_inat_uploaded),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.inatObservationId?.let { id ->
                                if (observationUrl != null) {
                                    TextButton(onClick = { onOpenObservation(observationUrl) }) {
                                        Text(stringResource(R.string.photos_inat_observation_id, id))
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.photos_inat_observation_id, id),
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
                            Text(stringResource(R.string.photos_inat_retry_sync))
                        }
                    }
                    observationUrl?.let { url ->
                        TextButton(onClick = { onOpenObservation(url) }) {
                            Text(stringResource(R.string.photos_workflow_open_inat))
                        }
                    }
                }
                else -> {
                    Text(stringResource(R.string.photos_inat_not_uploaded), style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = onUpload,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.photos_workflow_primary_upload))
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
                    text = stringResource(R.string.photos_identification_none),
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
                Text(stringResource(R.string.photos_sync_title), style = MaterialTheme.typography.titleMedium)
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
                            text = stringResource(R.string.photos_sync_in_progress),
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
                        Text(stringResource(R.string.photos_sync_try_again))
                    }
                }
                state.observationDetail != null -> {
                    val detail = state.observationDetail
                    Text(
                        text = stringResource(
                            R.string.photos_sync_summary,
                            detail.qualityGrade,
                            detail.agreeingIdCount,
                            detail.commentsCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.comments.isNotEmpty()) {
                        detail.comments.take(2).forEach { comment ->
                            Text(
                                text = stringResource(R.string.photos_sync_comment, comment.username, comment.body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (detail.commentsCount > detail.comments.size) {
                            Text(
                                text = stringResource(R.string.photos_sync_more_comments),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.photos_sync_no_comments),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.photos_sync_pull_hint),
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
    val selectedTargetLabel = selectedTarget.selectionLabel()

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
            Text(stringResource(R.string.photos_suggestions_title), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PhotoVisionTarget.entries.forEachIndexed { index, target ->
                    val enabled = ladder == null || PhotoVisionPlanner.chooseSuggestion(ladder, target) != null
                    SegmentedButton(
                        selected = selectedTarget == target,
                        onClick = { selectedTarget = target },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PhotoVisionTarget.entries.size,
                        ),
                        icon = {},
                        label = { Text(target.selectionLabel()) },
                    )
                }
            }

            if (ladder == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.photos_suggestions_none_loaded),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRetry,
                        enabled = state.canRunVision,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.photos_workflow_primary_get_suggestions))
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
                    Text(
                        stringResource(R.string.photos_suggestions_getting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        Text(stringResource(R.string.photos_sync_try_again))
                    }
                }
            } else if (!state.isUploaded) {
                Text(
                    stringResource(R.string.photos_suggestions_upload_first),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                                    onApplySuggestion(selectedSuggestion, selectedTargetLabel)
                                }
                            },
                        )
                    }
                    if (ladder.shouldShowAllToggle()) {
                        TextButton(onClick = { showAllSuggestions = !showAllSuggestions }) {
                            Text(
                                stringResource(
                                    if (showAllSuggestions) R.string.photos_suggestions_show_fewer else R.string.photos_suggestions_show_all
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoVisionTarget.selectionLabel(): String = stringResource(
    when (this) {
        PhotoVisionTarget.SPECIES -> R.string.photos_target_species
        PhotoVisionTarget.GENUS -> R.string.photos_target_genus
        PhotoVisionTarget.FAMILY -> R.string.photos_target_family
    },
)

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
                            text = stringResource(
                                R.string.photos_suggestion_rank_score,
                                suggestion.rank,
                                "%.1f".format(suggestion.score)
                            ),
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
                        Text(stringResource(R.string.photos_suggestions_apply))
                    }
                },
            )
        }
    }
}
