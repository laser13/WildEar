package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramDisplayRangeTest {

    @Test
    fun `BIRD_FOCUSED starts at 600 Hz`() {
        assertThat(SpectrogramDisplayRange.BIRD_FOCUSED.fMinHz).isEqualTo(600)
    }

    @Test
    fun `BIRD_FOCUSED ends at 10000 Hz`() {
        assertThat(SpectrogramDisplayRange.BIRD_FOCUSED.fMaxHz).isEqualTo(10_000)
    }

    @Test
    fun `WILDLIFE starts at 80 Hz`() {
        assertThat(SpectrogramDisplayRange.WILDLIFE.fMinHz).isEqualTo(80)
    }

    @Test
    fun `BIRDNET_BIRD covers 1 to 12 kHz`() {
        assertThat(SpectrogramDisplayRange.BIRDNET_BIRD.fMinHz).isEqualTo(1_000)
        assertThat(SpectrogramDisplayRange.BIRDNET_BIRD.fMaxHz).isEqualTo(12_000)
    }

    @Test
    fun `OWL_LOW_VOICE covers 80 to 6000 Hz`() {
        assertThat(SpectrogramDisplayRange.OWL_LOW_VOICE.fMinHz).isEqualTo(80)
        assertThat(SpectrogramDisplayRange.OWL_LOW_VOICE.fMaxHz).isEqualTo(6_000)
    }

    @Test
    fun `FULL starts at 0 Hz`() {
        assertThat(SpectrogramDisplayRange.FULL.fMinHz).isEqualTo(0)
    }

    @Test
    fun `all ranges have fMinHz less than fMaxHz`() {
        SpectrogramDisplayRange.entries.forEach { range ->
            assertThat(range.fMinHz).isLessThan(range.fMaxHz)
        }
    }

    @Test
    fun `all ranges have non-empty labels`() {
        SpectrogramDisplayRange.entries.forEach { range ->
            assertThat(range.displayName).isNotEmpty()
            assertThat(range.rangeLabel).isNotEmpty()
        }
    }
}
