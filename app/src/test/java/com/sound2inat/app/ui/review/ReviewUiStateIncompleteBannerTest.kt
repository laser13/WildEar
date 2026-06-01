package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [ReviewUiState.visibleIncompleteObservations]: an INCOMPLETE row is a
 * legitimate intermediate state during a live submission (between the
 * `Persisting` step and the final `markComplete` in
 * [com.sound2inat.inat.INatSubmitter]), so the "Incomplete observations" banner
 * must not surface species that the in-flight submission is actively uploading.
 */
class ReviewUiStateIncompleteBannerTest {

    private fun entry(name: String, rowId: Long) = IncompleteObsEntry(
        rowId = rowId,
        observationId = rowId * 10,
        scientificName = name,
        url = "https://inat/$rowId",
    )

    @Test
    fun `idle submission shows all incomplete rows`() {
        val state = ReviewUiState(
            draftId = "d1",
            incompleteObservations = listOf(entry("Gallinago gallinago", 1)),
            inatSubmission = InatSubmissionState.Idle,
        )

        assertThat(state.visibleIncompleteObservations)
            .isEqualTo(state.incompleteObservations)
    }

    @Test
    fun `active submission hides rows for species being uploaded now`() {
        val state = ReviewUiState(
            draftId = "d1",
            incompleteObservations = listOf(entry("Gallinago gallinago", 1)),
            inatSubmission = InatSubmissionState.InProgress,
            pendingSubmissionSpecies = listOf("Gallinago gallinago"),
        )

        assertThat(state.visibleIncompleteObservations).isEmpty()
    }

    @Test
    fun `active submission keeps stuck rows from a prior run not in this batch`() {
        val stuck = entry("Turdus merula", 2)
        val state = ReviewUiState(
            draftId = "d1",
            incompleteObservations = listOf(entry("Gallinago gallinago", 1), stuck),
            inatSubmission = InatSubmissionState.InProgress,
            pendingSubmissionSpecies = listOf("Gallinago gallinago"),
        )

        assertThat(state.visibleIncompleteObservations).containsExactly(stuck)
    }

    @Test
    fun `active submission with null pending species shows all rows`() {
        val state = ReviewUiState(
            draftId = "d1",
            incompleteObservations = listOf(entry("Gallinago gallinago", 1)),
            inatSubmission = InatSubmissionState.InProgress,
            pendingSubmissionSpecies = null,
        )

        assertThat(state.visibleIncompleteObservations)
            .isEqualTo(state.incompleteObservations)
    }
}
