package com.sound2inat.app.ui.review

import com.sound2inat.inference.ModelIds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet showing technical details for a single species detection.
 *
 * Row tap on the main list opens this sheet; closing it returns to the list.
 * Fragment time ranges are tappable — tapping calls [onSeekTo] with the
 * fragment's start position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeciesDetailsSheet(
    row: SpeciesRow,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    uploadedUrl: String? = null,
    onLoadDetail: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Title block — common name + scientific name
            item {
                val headline = row.taxonCommonName ?: row.taxonScientificName
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (row.taxonCommonName != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.taxonScientificName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            // Model scores
            item {
                Text(
                    text = "Model scores",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            val birdnetConf = row.confidenceBySource[ModelIds.BIRDNET]
            val perchConf = row.confidenceBySource[ModelIds.PERCH]

            if (birdnetConf != null) {
                item {
                    DetailRow(
                        label = "BirdNET",
                        value = "${(birdnetConf * SHEET_PERCENT).toInt()}%",
                    )
                }
            }
            if (perchConf != null) {
                item {
                    DetailRow(
                        label = "Perch",
                        value = "${(perchConf * SHEET_PERCENT).toInt()}%",
                    )
                }
            }
            if (birdnetConf == null && perchConf == null) {
                item {
                    DetailRow(
                        label = "Confidence",
                        value = "${(row.maxConfidence * SHEET_PERCENT).toInt()}%",
                    )
                }
            }

            // YAMNet gate — not stored in SpeciesRow yet (Task 7 adds it)
            item {
                DetailRow(label = "YAMNet gate", value = "N/A")
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            // Fragment info
            item {
                Text(
                    text = "Fragments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            item {
                DetailRow(label = "Count", value = "${row.detectedWindows}")
            }

            // Single time range from firstSeenMs / lastSeenMs
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Time ranges",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Use per-window fragment ranges when available; fall back to the
            // firstSeenMs/lastSeenMs bounds for pre-v5 (legacy) rows.
            val timeRanges = if (row.fragmentRanges.isNotEmpty()) {
                row.fragmentRanges.map { SpeciesTimeRange(it.startMs, it.endMs) }
            } else {
                listOf(SpeciesTimeRange(startMs = row.firstSeenMs, endMs = row.lastSeenMs))
            }
            items(timeRanges) { range ->
                Text(
                    text = formatSheetMs(range.startMs) + "–" + formatSheetMs(range.endMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onSeekTo(range.startMs) }
                        .padding(vertical = 4.dp)
                        .fillMaxWidth(),
                )
            }

            if (uploadedUrl != null) {
                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "iNaturalist",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                when (val detailState = row.observationDetailState) {
                    is ObservationDetailLoadState.NotLoaded -> {}
                    is ObservationDetailLoadState.Loading -> item {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    is ObservationDetailLoadState.Loaded -> {
                        val d = detailState.detail
                        item {
                            val idText = when (d.agreeingIdCount) {
                                0 -> "Awaiting IDs"
                                1 -> "1 ID · ${sheetQualityGradeLabel(d.qualityGrade)}"
                                else -> "${d.agreeingIdCount} IDs · ${sheetQualityGradeLabel(d.qualityGrade)}"
                            }
                            DetailRow(
                                label = "Status",
                                value = idText,
                                valueColor = if (d.qualityGrade == "research") SHEET_INAT_GREEN
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (d.taxonCommonName != null && d.qualityGrade == "research") {
                            item {
                                DetailRow(label = "Confirmed as", value = d.taxonCommonName)
                            }
                        }
                        if (d.commentsCount > 0) {
                            item {
                                DetailRow(
                                    label = "Comments",
                                    value = "${d.commentsCount}",
                                )
                            }
                        }
                    }
                    is ObservationDetailLoadState.Error -> item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Could not load iNat data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (onLoadDetail != null) {
                                TextButton(onClick = onLoadDetail) {
                                    Text("Retry", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

/**
 * A single displayable time range for a species detection.
 * Currently backed by [SpeciesRow.firstSeenMs]/[lastSeenMs]; future Tasks
 * may replace this with per-window ranges from the data layer.
 */
internal data class SpeciesTimeRange(val startMs: Long, val endMs: Long)

private val SHEET_INAT_GREEN = Color(0xFF74AC00)

private fun sheetQualityGradeLabel(grade: String): String = when (grade) {
    "research" -> "Research Grade"
    "needs_id"  -> "Needs ID"
    else        -> grade
}

internal fun formatSheetMs(ms: Long): String {
    val totalSeconds = (ms / SHEET_MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = totalSeconds / SHEET_SECONDS_PER_MINUTE
    val seconds = totalSeconds % SHEET_SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val SHEET_PERCENT = 100f
private const val SHEET_MS_PER_SECOND = 1000L
private const val SHEET_SECONDS_PER_MINUTE = 60L
