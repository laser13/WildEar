package com.sound2inat.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Sticky date-group header shared by every dated list (Home, Photos).
 * Visual reference: the audio tab's Home header — labelMedium, muted colour,
 * opaque background so list content scrolling underneath does not bleed
 * through while the header is pinned.
 */
@Composable
fun DateSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * Renders [groups] as a sequence of sticky-headered sections inside a
 * LazyColumn. Each group gets one pinned [DateSectionHeader] followed by its
 * items. This is the single place that defines dated-list sticky behaviour —
 * change it here and every list (Home, Photos) updates.
 */
@OptIn(ExperimentalFoundationApi::class)
fun <T> LazyListScope.datedSections(
    groups: List<DatedGroup<T>>,
    itemKey: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    groups.forEach { group ->
        stickyHeader(key = "date_header_${group.label}") {
            DateSectionHeader(group.label)
        }
        items(group.items, key = { itemKey(it) }) { item ->
            itemContent(item)
        }
    }
}
