package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewSpectrogramTimelineTest {

    @Test
    fun `5 seconds maps to minimum content width`() {
        assertThat(ReviewSpectrogramTimeline.contentWidthPx(5_000)).isEqualTo(1_200)
    }

    @Test
    fun `60 seconds maps to 6000 px`() {
        assertThat(ReviewSpectrogramTimeline.contentWidthPx(60_000)).isEqualTo(6_000)
    }

    @Test
    fun `10 minutes caps content width at 12000 px`() {
        assertThat(ReviewSpectrogramTimeline.contentWidthPx(600_000)).isEqualTo(12_000)
    }

    @Test
    fun `tap position and scroll offset convert to absolute seek time`() {
        assertThat(
            ReviewSpectrogramTimeline.seekMsFromTap(
                tapX = 300f,
                horizontalScrollPx = 500f,
                contentWidthPx = 6_000,
                durationMs = 60_000,
            ),
        ).isEqualTo(8_000L)
    }
}
