package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.SceneTags
import org.junit.Test

class AutoDisplayRangePickerTest {

    @Test
    fun `all scores below threshold returns null`() {
        val tags = SceneTags(bird = 0.29f, owl = 0.29f, frog = 0.29f, insect = 0.29f, mammal = 0.29f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags)).isNull()
    }

    @Test
    fun `score exactly at threshold counts as active`() {
        val tags = SceneTags(bird = 0.30f, owl = 0f, frog = 0f, insect = 0f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.BIRDNET_BIRD)
    }

    @Test
    fun `score just below threshold is inactive`() {
        val tags = SceneTags(bird = 0.2999f, owl = 0f, frog = 0f, insect = 0f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags)).isNull()
    }

    @Test
    fun `single active bird returns BIRDNET_BIRD`() {
        val tags = SceneTags(bird = 0.85f, owl = 0f, frog = 0f, insect = 0f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.BIRDNET_BIRD)
    }

    @Test
    fun `single active owl returns OWL_LOW_VOICE`() {
        val tags = SceneTags(bird = 0f, owl = 0.6f, frog = 0f, insect = 0f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.OWL_LOW_VOICE)
    }

    @Test
    fun `single active mammal returns WILDLIFE`() {
        val tags = SceneTags(bird = 0f, owl = 0f, frog = 0f, insect = 0f, mammal = 0.7f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.WILDLIFE)
    }

    @Test
    fun `bird plus insect collapse to INSECT_AMPHIBIAN`() {
        val tags = SceneTags(bird = 0.85f, owl = 0f, frog = 0f, insect = 0.45f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.INSECT_AMPHIBIAN)
    }

    @Test
    fun `owl plus bird requires FULL because no preset covers 80Hz-12kHz`() {
        val tags = SceneTags(bird = 0.5f, owl = 0.7f, frog = 0f, insect = 0f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.FULL)
    }

    @Test
    fun `owl plus insect requires FULL`() {
        val tags = SceneTags(bird = 0f, owl = 0.7f, frog = 0f, insect = 0.5f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.FULL)
    }

    @Test
    fun `frog plus insect both map to INSECT_AMPHIBIAN no duplicates`() {
        val tags = SceneTags(bird = 0f, owl = 0f, frog = 0.6f, insect = 0.4f, mammal = 0f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.INSECT_AMPHIBIAN)
    }

    @Test
    fun `mammal plus bird requires FULL because bird tops at 12k`() {
        val tags = SceneTags(bird = 0.7f, owl = 0f, frog = 0f, insect = 0f, mammal = 0.5f)
        assertThat(AutoDisplayRangePicker.pickDisplayRange(tags))
            .isEqualTo(SpectrogramDisplayRange.FULL)
    }
}
