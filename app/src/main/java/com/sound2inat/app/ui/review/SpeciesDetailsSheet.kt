package com.sound2inat.app.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

            val birdnetConf = row.confidenceBySource["birdnet_v2_4"]
            val perchConf = row.confidenceBySource["perch_v2"]

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

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
        )
    }
}

/**
 * A single displayable time range for a species detection.
 * Currently backed by [SpeciesRow.firstSeenMs]/[lastSeenMs]; future Tasks
 * may replace this with per-window ranges from the data layer.
 */
internal data class SpeciesTimeRange(val startMs: Long, val endMs: Long)

internal fun formatSheetMs(ms: Long): String {
    val totalSeconds = (ms / SHEET_MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = totalSeconds / SHEET_SECONDS_PER_MINUTE
    val seconds = totalSeconds % SHEET_SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val SHEET_PERCENT = 100f
private const val SHEET_MS_PER_SECOND = 1000L
private const val SHEET_SECONDS_PER_MINUTE = 60L
