package com.sound2inat.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One row of a submission progress checklist: a status glyph (spinner /
 * pending / done / failed) followed by [label] and an optional [subStatus].
 *
 * Visual reference: ReviewScreen's original ProgressRow — bodyMedium label
 * with subStatus rendered below it in a Column (not inline in the same Row).
 */
@Composable
fun ProgressRow(
    label: String,
    state: ProgressRowState,
    modifier: Modifier = Modifier,
    subStatus: String? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            ProgressRowState.InProgress -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            else -> {
                val icon = when (state) {
                    ProgressRowState.Pending -> Icons.Outlined.RadioButtonUnchecked
                    ProgressRowState.Done -> Icons.Outlined.CheckCircle
                    ProgressRowState.Failed -> Icons.Outlined.ErrorOutline
                    ProgressRowState.InProgress -> error("unreachable")
                }
                val tint = when (state) {
                    ProgressRowState.Pending -> MaterialTheme.colorScheme.outline
                    ProgressRowState.Done -> MaterialTheme.colorScheme.primary
                    ProgressRowState.Failed -> MaterialTheme.colorScheme.error
                    ProgressRowState.InProgress -> error("unreachable")
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subStatus != null) {
                Text(
                    subStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Visual status of a single submission-checklist row. */
enum class ProgressRowState { Pending, InProgress, Done, Failed }
