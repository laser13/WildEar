package com.sound2inat.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.DraftStatus
import org.junit.Test

class HomeCopyTest {
    @Test
    fun `draft status label is concise`() {
        assertThat(homeStatusLabel(DraftStatus.PENDING_INFERENCE, analysedButEmpty = false))
            .isEqualTo("Analyzing")
        assertThat(homeStatusLabel(DraftStatus.PENDING_REVIEW, analysedButEmpty = false))
            .isEqualTo("Needs review")
        assertThat(homeStatusLabel(DraftStatus.REVIEWED, analysedButEmpty = false))
            .isEqualTo("Not submitted")
        assertThat(homeStatusLabel(DraftStatus.UPLOADED, analysedButEmpty = false))
            .isEqualTo("Submitted")
        assertThat(homeStatusLabel(DraftStatus.REVIEWED, analysedButEmpty = true))
            .isEqualTo("No detections")
    }
}
