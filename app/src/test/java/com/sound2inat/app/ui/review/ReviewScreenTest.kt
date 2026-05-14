package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewScreenTest {
    @Test
    fun `active page with audio should ensure visuals`() {
        assertThat(shouldEnsureVisualsForPage(isActive = true, audioPath = "/tmp/audio.wav")).isTrue()
    }

    @Test
    fun `inactive page with audio should not ensure visuals`() {
        assertThat(shouldEnsureVisualsForPage(isActive = false, audioPath = "/tmp/audio.wav")).isFalse()
    }

    @Test
    fun `active page without audio should not ensure visuals`() {
        assertThat(shouldEnsureVisualsForPage(isActive = true, audioPath = null)).isFalse()
    }
}
