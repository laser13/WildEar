package com.sound2inat.app.ui

/** Formats a duration in milliseconds as `m:ss`. Clamps negative values to zero. */
fun formatDurationMs(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    return "%d:%02d".format(t / 60_000L, t / 1_000L % 60L)
}
