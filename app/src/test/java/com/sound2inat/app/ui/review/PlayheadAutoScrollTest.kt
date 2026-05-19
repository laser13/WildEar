package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayheadAutoScrollTest {
    @Test
    fun `auto-follow keeps scroll at zero while cursor is near the start`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 50f,
            currentScroll = 0,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        // 50 - 400/2 = -150 → clamp to 0; already at 0, so target == null.
        assertThat(decision.targetScroll).isNull()
    }

    @Test
    fun `auto-follow centers the cursor in the middle of the strip`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 1_000f,
            currentScroll = 0,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        assertThat(decision.targetScroll).isEqualTo(800)
    }

    @Test
    fun `auto-follow clamps to maxScroll near the end of the strip`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 2_350f,
            currentScroll = 1_900,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        assertThat(decision.targetScroll).isEqualTo(2_000)
    }

    @Test
    fun `auto-follow returns null target when scroll value is already correct`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 1_000f,
            currentScroll = 800,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        assertThat(decision.targetScroll).isNull()
    }

    @Test
    fun `manual scroll keeps auto-follow off while cursor stays outside viewport`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = false,
            cursorPx = 1_500f,
            currentScroll = 0,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isFalse()
        assertThat(decision.targetScroll).isNull()
    }

    @Test
    fun `auto-follow re-arms when cursor re-enters the visible window`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = false,
            cursorPx = 150f,
            currentScroll = 0,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        // 150 - 400/2 = -50 → clamp to 0; already 0, so target == null.
        assertThat(decision.targetScroll).isNull()
    }

    @Test
    fun `auto-follow re-arms mid-strip and centers the cursor`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = false,
            cursorPx = 1_300f,
            currentScroll = 1_000,
            viewportSize = 400,
            maxScroll = 2_000,
        )

        assertThat(decision.newAutoFollow).isTrue()
        assertThat(decision.targetScroll).isEqualTo(1_100)
    }

    @Test
    fun `unmeasured viewport returns a no-op decision preserving the auto-follow flag`() {
        val onState = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 800f,
            currentScroll = 0,
            viewportSize = 0,
            maxScroll = 2_000,
        )
        assertThat(onState.newAutoFollow).isTrue()
        assertThat(onState.targetScroll).isNull()

        val offState = PlayheadAutoScroll.decide(
            autoFollow = false,
            cursorPx = 800f,
            currentScroll = 0,
            viewportSize = 0,
            maxScroll = 2_000,
        )
        assertThat(offState.newAutoFollow).isFalse()
        assertThat(offState.targetScroll).isNull()
    }

    @Test
    fun `no-op when strip fits entirely in the viewport`() {
        val decision = PlayheadAutoScroll.decide(
            autoFollow = true,
            cursorPx = 600f,
            currentScroll = 0,
            viewportSize = 1_200,
            maxScroll = 0,
        )

        assertThat(decision.newAutoFollow).isTrue()
        assertThat(decision.targetScroll).isNull()
    }
}
