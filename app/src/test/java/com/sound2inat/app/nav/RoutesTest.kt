package com.sound2inat.app.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutesTest {
    @Test
    fun `route helpers build photo routes`() {
        assertThat(Routes.photoCapture()).isEqualTo("photo_capture")
        assertThat(Routes.photoCapture("d1")).isEqualTo("photo_capture?draftId=d1")
        assertThat(Routes.photoReview("p1")).isEqualTo("photo_review/p1")
    }
}
