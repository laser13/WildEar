package com.sound2inat.app.ui.spectrogram

import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.SpectrogramRenderProfile
import org.junit.Test

class SpectrogramRenderProfileTest {

    @Test
    fun `LiveBird covers the bird-relevant audible window`() {
        val live = SpectrogramRenderProfile.LiveBird

        assertThat(live.minFrequencyHz).isLessThan(live.maxFrequencyHz)
        assertThat(live.maxFrequencyHz).isAtMost(10_000)
        assertThat(live.gateDb).isAtLeast(0f)
        assertThat(live.displayRangeDb).isGreaterThan(0f)
        assertThat(live.gamma).isGreaterThan(0f)
    }
}
