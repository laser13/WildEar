package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReviewSubmissionCoordinatorTest {

    @Test
    fun `resolveToken returns NeedsInteractiveLogin on first null token`() = runTest {
        val coordinator = ReviewSubmissionCoordinator(
            tokenProvider = { null },
            acceptInatToken = { },
            submission = InatSubmissionJob { _, _, _, _, _, _ -> InatSubmissionOutcome.Failure("x") },
        )

        val result = coordinator.resolveToken()

        assertThat(result).isInstanceOf(TokenResolution.NeedsInteractiveLogin::class.java)
    }

    @Test
    fun `resolveToken returns Expired after an interactive attempt already failed`() = runTest {
        val coordinator = ReviewSubmissionCoordinator(
            tokenProvider = { null },
            acceptInatToken = { },
            submission = InatSubmissionJob { _, _, _, _, _, _ -> InatSubmissionOutcome.Failure("x") },
        )
        coordinator.markInteractiveLoginAttempted()

        val result = coordinator.resolveToken()

        assertThat(result).isInstanceOf(TokenResolution.Expired::class.java)
    }

    @Test
    fun `resolveToken returns Ready with a non-blank token`() = runTest {
        val coordinator = ReviewSubmissionCoordinator(
            tokenProvider = { "jwt" },
            acceptInatToken = { },
            submission = InatSubmissionJob { _, _, _, _, _, _ -> InatSubmissionOutcome.Success(emptyList()) },
        )

        val result = coordinator.resolveToken()

        assertThat(result).isEqualTo(TokenResolution.Ready("jwt"))
    }

    @Test
    fun `clearLoginGuard resets the interactive-login flag`() = runTest {
        val coordinator = ReviewSubmissionCoordinator(
            tokenProvider = { null },
            acceptInatToken = { },
            submission = InatSubmissionJob { _, _, _, _, _, _ -> InatSubmissionOutcome.Failure("x") },
        )
        coordinator.markInteractiveLoginAttempted()
        coordinator.clearLoginGuard()

        // After clearing, a fresh null token requests interactive login again.
        assertThat(coordinator.resolveToken())
            .isInstanceOf(TokenResolution.NeedsInteractiveLogin::class.java)
    }
}
