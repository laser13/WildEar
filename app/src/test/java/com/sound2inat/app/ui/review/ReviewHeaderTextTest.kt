package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewHeaderTextTest {

    @Test
    fun `formats timestamp and gps coordinates in the header text`() {
        val header = reviewHeaderText(
            recordedAtUtcMs = 0L,
            latitude = 12.34567,
            longitude = -98.76543,
        )

        assertThat(header).isEqualTo(
            ReviewHeaderText(
                titleLine = "1970-01-01 00:00",
                subtitleLine = "GPS: 12.3457, -98.7654",
            ),
        )
    }

    @Test
    fun `uses location unavailable fallback when coordinates are missing`() {
        val header = reviewHeaderText(
            recordedAtUtcMs = 0L,
            latitude = null,
            longitude = null,
        )

        assertThat(header.subtitleLine).isEqualTo("Location unavailable")
    }
}
