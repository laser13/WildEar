package com.sound2inat.app.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A labelled group of items sharing a calendar-day bucket. */
data class DatedGroup<T>(val label: String, val items: List<T>)

/**
 * Groups [items] by the local calendar day of the timestamp returned by
 * [timestampOf]. Every group is labelled with a concrete date in the device
 * locale (e.g. "2 июня 2026" / "2 June 2026") — no relative "Today"/"Yesterday"
 * labels. Items are sorted newest-first within each group and groups themselves
 * appear newest-first.
 *
 * Timestamps that fall on the same local calendar day produce the same label
 * and therefore collapse into one group.
 *
 * [now] is retained for API/signature compatibility but is no longer used now
 * that labels are absolute dates rather than relative to the current day.
 */
fun <T> groupDatedItems(
    items: List<T>,
    @Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis(),
    timestampOf: (T) -> Long,
): List<DatedGroup<T>> {
    if (items.isEmpty()) return emptyList()
    val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.systemDefault())
    val groups = LinkedHashMap<String, MutableList<T>>()
    for (item in items.sortedByDescending(timestampOf)) {
        val label = dateFmt.format(Instant.ofEpochMilli(timestampOf(item)))
        groups.getOrPut(label) { mutableListOf() }.add(item)
    }
    return groups.map { (label, items) -> DatedGroup(label, items) }
}
