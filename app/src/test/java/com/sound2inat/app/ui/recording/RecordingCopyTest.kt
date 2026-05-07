package com.sound2inat.app.ui.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingCopyTest {
    @Test
    fun `empty live recording wording uses wildlife`() {
        assertThat(LIVE_LISTENING_LABEL).isEqualTo("Listening for wildlife...")
    }

    @Test
    fun `live matches section title is possible matches`() {
        assertThat(LIVE_MATCHES_TITLE).isEqualTo("Possible matches")
    }
}
