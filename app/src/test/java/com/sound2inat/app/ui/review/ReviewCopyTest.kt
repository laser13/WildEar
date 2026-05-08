package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewCopyTest {
    @Test
    fun `species row trailing label is compact confidence and fragment count`() {
        assertThat(speciesRowTrailingLabel(confidence = 0.804f, detectedWindows = 8))
            .isEqualTo("80% ×8")
    }
}
