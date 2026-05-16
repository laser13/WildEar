package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewSettingsBottomSheetPolicyTest {

    @Test
    fun `visual settings apply immediately`() {
        assertThat(ReviewSettingsTab.Visual.appliesImmediately()).isTrue()
        assertThat(ReviewSettingsTab.Audio.appliesImmediately()).isFalse()
    }

    @Test
    fun `only audio tab shows confirmation buttons`() {
        assertThat(ReviewSettingsTab.Audio.showsConfirmationButtons()).isTrue()
        assertThat(ReviewSettingsTab.Visual.showsConfirmationButtons()).isFalse()
    }
}
