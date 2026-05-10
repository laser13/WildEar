package com.sound2inat.app.ui

/** Formats a duration in milliseconds as `m:ss`. */
fun formatDurationMs(ms: Long): String =
    "%d:%02d".format(ms / 60_000L, ms / 1_000L % 60L)
