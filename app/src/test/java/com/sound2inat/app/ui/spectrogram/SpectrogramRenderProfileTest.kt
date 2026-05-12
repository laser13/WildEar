package com.sound2inat.app.ui.spectrogram

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramRenderProfileTest {

    @Test
    fun `LiveBird keeps a more visible display window than review`() {
        val live = SpectrogramRenderProfile.LiveBird
        val review = SpectrogramRenderProfile.ReviewBird

        assertThat(live.minFrequencyHz).isLessThan(review.minFrequencyHz)
        assertThat(live.gateDb).isLessThan(review.gateDb)
        assertThat(live.displayRangeDb).isGreaterThan(review.displayRangeDb)
        assertThat(live.smoothingTimeRadius).isGreaterThan(review.smoothingTimeRadius)
        assertThat(live.smoothingFrequencyRadius).isGreaterThan(review.smoothingFrequencyRadius)
    }
}
