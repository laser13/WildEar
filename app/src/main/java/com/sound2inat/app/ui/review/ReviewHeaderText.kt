package com.sound2inat.app.ui.review

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReviewHeaderText(
    val titleLine: String,
    val subtitleLine: String,
)

fun reviewHeaderText(
    recordedAtUtcMs: Long,
    latitude: Double?,
    longitude: Double?,
): ReviewHeaderText {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(recordedAtUtcMs))
    val subtitle = if (latitude != null && longitude != null) {
        String.format(Locale.US, "GPS: %.4f, %.4f", latitude, longitude)
    } else {
        "Location unavailable"
    }
    return ReviewHeaderText(
        titleLine = timestamp,
        subtitleLine = subtitle,
    )
}
