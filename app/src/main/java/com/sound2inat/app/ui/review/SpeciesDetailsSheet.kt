package com.sound2inat.app.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sound2inat.app.R
import com.sound2inat.app.ui.formatDurationMs
import com.sound2inat.app.ui.theme.iNatGreen
import com.sound2inat.inference.ModelIds

/**
 * Bottom sheet showing technical details for a single species detection.
 *
 * Row tap on the main list opens this sheet; closing it returns to the list.
 * Fragment time ranges are tappable — tapping calls [onSeekTo] with the
 * fragment's start position.
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeciesDetailsSheet(
    row: SpeciesRow,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    uploadedUrl: String? = null,
    onLoadDetail: (() -> Unit)? = null,
    exportingAction: ExportingAction? = null,
    onShareClip: () -> Unit = {},
    onSaveClip: () -> Unit = {},
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

            val birdnetConf = row.confidenceBySource[ModelIds.BIRDNET]
            val perchConf = row.confidenceBySource[ModelIds.PERCH]

            if (birdnetConf != null) {
                item {
                    DetailRow(
                        label = stringResource(R.string.sheet_label_birdnet),
                        value = "${(birdnetConf * SHEET_PERCENT).toInt()}%",
                    )
                }
            }
            if (perchConf != null) {
                item {
                    DetailRow(
                        label = stringResource(R.string.sheet_label_perch),
                        value = "${(perchConf * SHEET_PERCENT).toInt()}%",
                    )
                }
            }
            if (birdnetConf == null && perchConf == null) {
                item {
                    DetailRow(
                        label = stringResource(R.string.sheet_label_confidence),
                        value = "${(row.maxConfidence * SHEET_PERCENT).toInt()}%",
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            // Fragment info
            item {
                Text(
                    text = stringResource(R.string.sheet_fragments_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            item {
                DetailRow(label = stringResource(R.string.sheet_label_count), value = "${row.detectedWindows}")
            }

            // Single time range from firstSeenMs / lastSeenMs
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sheet_time_ranges_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Use per-window fragment ranges when available; fall back to the
            // firstSeenMs/lastSeenMs bounds for pre-v5 (legacy) rows.
            // Ranges are sorted and merged so overlapping 3-second windows from
            // multiple models collapse into a single continuous interval.
            val timeRanges = if (row.fragmentRanges.isNotEmpty()) {
                mergeRanges(row.fragmentRanges.map { SpeciesTimeRange(it.startMs, it.endMs) })
            } else {
                listOf(SpeciesTimeRange(startMs = row.firstSeenMs, endMs = row.lastSeenMs))
            }
            items(timeRanges) { range ->
                Text(
                    text = formatDurationMs(range.startMs) + "–" + formatDurationMs(range.endMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onSeekTo(range.startMs) }
                        .padding(vertical = 4.dp)
                        .fillMaxWidth(),
                )
            }

            // Clip export buttons — Share clip / Save clip
            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    val isShareLoading = (exportingAction as? ExportingAction.SpeciesClipShare)
                        ?.detectionId == row.detectionId
                    val isSaveLoading = (exportingAction as? ExportingAction.SpeciesClipSave)
                        ?.detectionId == row.detectionId
                    val isAnyExporting = exportingAction != null

                    TextButton(onClick = onShareClip, enabled = !isAnyExporting) {
                        if (isShareLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_share_clip))
                    }
                    TextButton(onClick = onSaveClip, enabled = !isAnyExporting) {
                        if (isSaveLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_save_clip))
                    }
                }
            }

            if (uploadedUrl != null) {
                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.sheet_inat_section),
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
                            val gradeLabel = when (d.qualityGrade) {
                                "research" -> stringResource(R.string.sheet_quality_research)
                                "needs_id" -> stringResource(R.string.sheet_quality_needs_id)
                                else -> d.qualityGrade
                            }
                            val idText = when (d.agreeingIdCount) {
                                0 -> stringResource(R.string.sheet_awaiting_ids)
                                1 -> "1 ID · $gradeLabel"
                                else -> "${d.agreeingIdCount} IDs · $gradeLabel"
                            }
                            DetailRow(
                                label = stringResource(R.string.sheet_label_status),
                                value = idText,
                                valueColor = if (d.qualityGrade == "research") {
                                    iNatGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (d.taxonCommonName != null && d.qualityGrade == "research") {
                            item {
                                DetailRow(
                                    label = stringResource(R.string.sheet_label_confirmed_as),
                                    value = d.taxonCommonName
                                )
                            }
                        }
                        if (d.commentsCount > 0) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.sheet_label_comments),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            if (d.comments.isNotEmpty()) {
                                items(d.comments) { comment ->
                                    HorizontalDivider()
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        Text(
                                            text = "@${comment.username}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = comment.body,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (d.commentsCount > d.comments.size) {
                                    item {
                                        Text(
                                            text = stringResource(
                                                R.string.sheet_comments_more,
                                                d.commentsCount - d.comments.size,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                }
                            } else {
                                // Edge case: API returned commentsCount > 0 but no comment bodies
                                item {
                                    DetailRow(
                                        label = stringResource(R.string.sheet_label_comments),
                                        value = "${d.commentsCount}",
                                    )
                                }
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
                                stringResource(R.string.sheet_error_load_inat),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (onLoadDetail != null) {
                                TextButton(onClick = onLoadDetail) {
                                    Text(stringResource(R.string.btn_retry), style = MaterialTheme.typography.bodySmall)
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

/**
 * Sorts ranges by start time and merges overlapping or adjacent ones into
 * continuous intervals. Detections from multiple models arrive interleaved
 * and each window is 3 s wide with a 1 s hop, so consecutive detections
 * produce ranges like 0:18–0:21, 0:19–0:22, … that should collapse to a
 * single span.
 */
internal fun mergeRanges(ranges: List<SpeciesTimeRange>): List<SpeciesTimeRange> {
    if (ranges.size <= 1) return ranges
    val sorted = ranges.sortedBy { it.startMs }
    val result = mutableListOf(sorted[0])
    for (r in sorted.drop(1)) {
        val last = result.last()
        if (r.startMs <= last.endMs) {
            result[result.lastIndex] = last.copy(endMs = maxOf(last.endMs, r.endMs))
        } else {
            result += r
        }
    }
    return result
}

private const val SHEET_PERCENT = 100f
