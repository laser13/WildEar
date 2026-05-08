package com.sound2inat.app.ui.radar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private val RADIUS_OPTIONS = listOf(1, 5, 25, 100)
private val PERIOD_OPTIONS = listOf(1, 7, 30)

@Composable
internal fun RadarFilterBar(
    filter: FilterState,
    onRadiusChange: (Int) -> Unit,
    onPeriodChange: (Int) -> Unit,
    onTaxaToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ChipRow(label = "Radius") {
            RADIUS_OPTIONS.forEach { km ->
                FilterChip(
                    selected = filter.radiusKm == km,
                    onClick = { onRadiusChange(km) },
                    label = { Text("$km km") },
                )
            }
        }
        ChipRow(label = "Period") {
            PERIOD_OPTIONS.forEach { d ->
                FilterChip(
                    selected = filter.periodDays == d,
                    onClick = { onPeriodChange(d) },
                    label = { Text(periodLabel(d)) },
                )
            }
        }
        ChipRow(label = "Groups") {
            FilterableIconicTaxa.forEach { t ->
                FilterChip(
                    selected = t.id in filter.taxa,
                    onClick = { onTaxaToggle(t.id) },
                    leadingIcon = {
                        Icon(painterResource(t.icon), contentDescription = null)
                    },
                    label = { Text(t.label) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

private fun periodLabel(days: Int): String = when (days) {
    1 -> "Day"
    7 -> "Week"
    30 -> "Month"
    else -> "$days d"
}
