package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.DraftStatus
import org.junit.Test

class ReviewCopyTest {
    @Test
    fun `review section title uses wildlife wording`() {
        assertThat(REVIEW_SPECIES_SECTION_TITLE).isEqualTo("Detected wildlife")
    }

    @Test
    fun `submit button labels match selection count`() {
        assertThat(reviewSubmitLabel(DraftStatus.REVIEWED, InatSubmissionState.Idle, selectedCount = 0))
            .isEqualTo("Select species")
        assertThat(reviewSubmitLabel(DraftStatus.REVIEWED, InatSubmissionState.Idle, selectedCount = 1))
            .isEqualTo("Submit 1 selected")
        assertThat(reviewSubmitLabel(DraftStatus.REVIEWED, InatSubmissionState.Idle, selectedCount = 3))
            .isEqualTo("Submit 3 selected")
    }

    @Test
    fun `species row trailing label is compact confidence and fragment count`() {
        assertThat(speciesRowTrailingLabel(confidence = 0.804f, detectedWindows = 8))
            .isEqualTo("80% ×8")
    }

}
