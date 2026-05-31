package com.sound2inat.app.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** A labelled group of items sharing a calendar-day bucket. */
data class DatedGroup<T>(val label: String, val items: List<T>)

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val WEEK_DAYS = 7

/**
 * Groups [items] by the local calendar day of the timestamp returned by
 * [timestampOf]. Today / Yesterday get human labels; days within a week show
 * weekday + date (e.g. "Friday, May 1"); older entries fall back to a full
 * date stamp (e.g. "January 15, 2026"). Items are sorted newest-first within
 * each group and groups themselves appear newest-first.
 */
fun <T> groupDatedItems(
    items: List<T>,
    now: Long = System.currentTimeMillis(),
    timestampOf: (T) -> Long,
): List<DatedGroup<T>> {
    if (items.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val todayStart = startOfDay(now, cal)
    val yesterdayStart = todayStart - DAY_MS
    val zone = ZoneId.systemDefault()
    val recentFmt = DateTimeFormatter.ofPattern("EEEE, MMM d").withZone(zone)
    val olderFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(zone)
    val groups = LinkedHashMap<String, MutableList<T>>()
    for (item in items.sortedByDescending(timestampOf)) {
        val ts = timestampOf(item)
        val dayStart = startOfDay(ts, cal)
        val label = when {
            dayStart >= todayStart -> "Today"
            dayStart >= yesterdayStart -> "Yesterday"
            (todayStart - dayStart) < WEEK_DAYS * DAY_MS ->
                recentFmt.format(Instant.ofEpochMilli(ts))
            else -> olderFmt.format(Instant.ofEpochMilli(ts))
        }
        groups.getOrPut(label) { mutableListOf() }.add(item)
    }
    return groups.map { (label, items) -> DatedGroup(label, items) }
}

private fun startOfDay(ms: Long, cal: Calendar): Long {
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
