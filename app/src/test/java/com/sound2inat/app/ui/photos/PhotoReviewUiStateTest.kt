package com.sound2inat.app.ui.photos

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.common.ProgressRowState
import com.sound2inat.inat.InatTaxonInfo
import com.sound2inat.inat.InatVisionCandidate
import com.sound2inat.inat.InatVisionResponse
import com.sound2inat.inat.PhotoVisionLadder
import com.sound2inat.inat.PhotoVisionPlanner
import com.sound2inat.inat.PhotoVisionSuggestion
import com.sound2inat.inat.SubmissionProgress
import org.junit.Test

class PhotoReviewUiStateTest {
    @Test
    fun `upload state is normalized from observation url fields`() {
        val notUploaded = PhotoReviewUiState()
        val uploaded = PhotoReviewUiState(uploadedUrl = "https://inat.test/observations/1")
        val synced = PhotoReviewUiState(inatObservationUrl = "https://inat.test/observations/2")

        assertThat(notUploaded.isUploaded).isFalse()
        assertThat(notUploaded.observationUrl).isNull()
        assertThat(uploaded.isUploaded).isTrue()
        assertThat(uploaded.observationUrl).isEqualTo("https://inat.test/observations/1")
        assertThat(synced.isUploaded).isTrue()
        assertThat(synced.observationUrl).isEqualTo("https://inat.test/observations/2")
    }

    @Test
    fun `isUploaded is true when only inatObservationId is set`() {
        // inatObservationId alone (without url) should be enough to flag as uploaded
        val state = PhotoReviewUiState(inatObservationId = 42L)
        assertThat(state.isUploaded).isTrue()
    }

    @Test
    fun `isUploaded is false when all three uploaded fields are null`() {
        val state = PhotoReviewUiState(
            inatObservationId = null,
            inatObservationUrl = null,
            uploadedUrl = null,
        )
        assertThat(state.isUploaded).isFalse()
    }

    @Test
    fun `observationUrl prefers inatObservationUrl from Room over optimistic uploadedUrl`() {
        // After Room emits, inatObservationUrl takes priority over the optimistic URL
        val state = PhotoReviewUiState(
            uploadedUrl = "https://inat.test/observations/1",
            inatObservationUrl = "https://inat.test/observations/1",
        )
        assertThat(state.observationUrl).isEqualTo("https://inat.test/observations/1")
        assertThat(state.isUploaded).isTrue()
    }

    @Test
    fun `optimistic uploadedUrl bridges the gap before Room emits inatObservationUrl`() {
        // Immediately after submit(), only uploadedUrl is set; inatObservationUrl is still null
        val optimisticState = PhotoReviewUiState(
            uploadedUrl = "https://inat.test/observations/99",
            inatObservationUrl = null,
            inatObservationId = null,
        )
        assertThat(optimisticState.isUploaded).isTrue()
        assertThat(optimisticState.observationUrl).isEqualTo("https://inat.test/observations/99")
    }

    @Test
    fun `incomplete recovery banner is hidden while upload is still running`() {
        val incomplete = IncompletePhotoObservationUi(
            observationId = 42L,
            scientificName = "Photo observation",
            url = "https://inat.test/observations/42",
        )

        assertThat(
            PhotoReviewUiState(
                isSubmitting = true,
                incompleteObservation = incomplete,
            ).shouldShowIncompleteRecovery,
        ).isFalse()
        assertThat(
            PhotoReviewUiState(
                isSubmitting = false,
                incompleteObservation = incomplete,
            ).shouldShowIncompleteRecovery,
        ).isTrue()
    }

    @Test
    fun `resolved observation id is derived from the observation url`() {
        val state = PhotoReviewUiState(uploadedUrl = "https://www.inaturalist.org/observations/777")

        assertThat(state.resolvedObservationId).isEqualTo(777L)
    }

    @Test
    fun `unavailable genus and family actions are hidden from the ladder`() {
        val ladder = PhotoVisionLadder(
            topCandidates = listOf(
                PhotoVisionSuggestion(
                    taxonId = 101L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.91,
                    iconicTaxonName = "Insecta",
                ),
            ),
        )

        assertThat(ladder.availableRankActions()).isEmpty()
    }

    @Test
    fun `available rank actions include only genus and family suggestions when present`() {
        val ladder = PhotoVisionLadder(
            topCandidates = listOf(
                PhotoVisionSuggestion(
                    taxonId = 101L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.91,
                    iconicTaxonName = "Insecta",
                ),
            ),
            higherTaxa = listOf(
                PhotoVisionSuggestion(
                    taxonId = 102L,
                    scientificName = "Ammophila",
                    commonName = null,
                    rank = "genus",
                    rankLevel = 20,
                    score = 0.61,
                    iconicTaxonName = "Insecta",
                ),
                PhotoVisionSuggestion(
                    taxonId = 103L,
                    scientificName = "Vespidae",
                    commonName = "Paper wasps",
                    rank = "family",
                    rankLevel = 30,
                    score = 0.49,
                    iconicTaxonName = "Insecta",
                ),
            ),
        )

        assertThat(ladder.availableRankActions().map { it.label }).containsExactly(
            "Use genus only",
            "Use family only",
        ).inOrder()
    }

    @Test
    fun `photo vision target selector is a compact segmented control without only labels`() {
        val source = java.io.File(
            "src/main/java/com/sound2inat/app/ui/photos/PhotoReviewWorkflowCards.kt"
        ).readText()
        val cardStart = source.indexOf("fun PhotoVisionSuggestionsCard")
        val cardEnd = source.indexOf("fun ObservationSuggestionCard")
        val cardSource = source.substring(cardStart, cardEnd)

        assertThat(cardSource).contains("SingleChoiceSegmentedButtonRow")
        assertThat(cardSource).contains("SegmentedButton(")
        assertThat(cardSource).contains("icon = {}")
        assertThat(cardSource).doesNotContain("Species only")
        assertThat(cardSource).doesNotContain("Genus only")
        assertThat(cardSource).doesNotContain("Family only")
    }

    @Test
    fun `incomplete photo banner keeps title separate from action buttons`() {
        val source = java.io.File(
            "src/main/java/com/sound2inat/app/ui/photos/PhotoReviewScreen.kt"
        ).readText()
        val bannerStart = source.indexOf("fun PhotoIncompleteObservationBanner")
        val bannerEnd = source.indexOf("if (confirmOpen)", startIndex = bannerStart)
        val bannerSource = source.substring(bannerStart, bannerEnd)

        assertThat(bannerSource).contains("Modifier.fillMaxWidth()")
        assertThat(bannerSource).contains("horizontalArrangement = Arrangement.End")
        assertThat(bannerSource).contains(".padding(bottom = 2.dp)")
    }

    @Test
    fun `genus target does not fall back to species when genus is unavailable`() {
        val ladder = PhotoVisionLadder(
            topCandidates = listOf(
                PhotoVisionSuggestion(
                    taxonId = 101L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.91,
                    iconicTaxonName = "Insecta",
                ),
            ),
        )

        assertThat(PhotoVisionPlanner.chooseSuggestion(ladder, com.sound2inat.inat.PhotoVisionTarget.GENUS))
            .isNull()
    }

    @Test
    fun `collect ancestor ids includes candidate taxon ids even when ancestry omits them`() {
        val response = InatVisionResponse(
            candidates = listOf(
                InatVisionCandidate(
                    taxonId = 102L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.71,
                    ancestry = "1/2/101",
                    iconicTaxonName = "Insecta",
                ),
            ),
        )

        assertThat(PhotoVisionPlanner.collectAncestorIds(response)).containsExactly(1L, 2L, 101L, 102L)
    }

    @Test
    fun `build ladder uses only exact taxon photo not ancestor photo`() {
        val response = InatVisionResponse(
            candidates = listOf(
                InatVisionCandidate(
                    taxonId = 102L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.71,
                    ancestry = "1/2/101",
                    iconicTaxonName = "Insecta",
                ),
            ),
        )
        val ladder = PhotoVisionPlanner.buildLadder(
            response = response,
            taxonInfo = mapOf(
                101L to InatTaxonInfo(
                    scientificName = "Ammophila",
                    commonName = null,
                    rank = "genus",
                    rankLevel = 20,
                    iconicTaxonName = "Insecta",
                    photoUrl = "https://example/genus.jpg",
                ),
                102L to InatTaxonInfo(
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    iconicTaxonName = "Insecta",
                    photoUrl = null,
                ),
            ),
        )

        assertThat(ladder.topCandidates.single().photoUrl).isNull()
    }

    @Test
    fun `visible suggestions default to top three and can expand`() {
        val ladder = PhotoVisionLadder(
            topCandidates = listOf(
                suggestion(1L, "Alpha"),
                suggestion(2L, "Bravo"),
                suggestion(3L, "Charlie"),
                suggestion(4L, "Delta"),
            ),
        )

        assertThat(ladder.visibleSuggestions(showAll = false).map { it.scientificName }).containsExactly(
            "Alpha",
            "Bravo",
            "Charlie",
        ).inOrder()
        assertThat(ladder.visibleSuggestions(showAll = true).map { it.scientificName }).containsExactly(
            "Alpha",
            "Bravo",
            "Charlie",
            "Delta",
        ).inOrder()
        assertThat(ladder.shouldShowAllToggle()).isTrue()
    }

    // ── photoProgressRowState ────────────────────────────────────────────────

    @Test
    fun `photoProgressRowState failure at UploadingPrimaryPhoto marks that row Failed earlier rows Done later rows Pending`() {
        val failedAt = SubmissionProgress.Step.UploadingPrimaryPhoto
        val terminal = SubmissionProgress.Step.DoneFailed

        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.CreatingObservation)
        ).isEqualTo(ProgressRowState.Done)

        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.UploadingPrimaryPhoto)
        ).isEqualTo(ProgressRowState.Failed)

        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.UploadingExtraPhoto)
        ).isEqualTo(ProgressRowState.Pending)
        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.ApplyingTag)
        ).isEqualTo(ProgressRowState.Pending)
        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.Persisting)
        ).isEqualTo(ProgressRowState.Pending)
    }

    @Test
    fun `photoProgressRowState unknown failedStep does NOT mislabel row 0 as Failed`() {
        val terminal = SubmissionProgress.Step.DoneFailed
        assertThat(
            photoProgressRowState(terminal, null, SubmissionProgress.Step.CreatingObservation)
        ).isEqualTo(ProgressRowState.Pending)
        assertThat(
            photoProgressRowState(terminal, null, SubmissionProgress.Step.UploadingPrimaryPhoto)
        ).isEqualTo(ProgressRowState.Pending)
    }

    @Test
    fun `photoProgressRowState DoneOk marks all rows Done`() {
        val terminal = SubmissionProgress.Step.DoneOk
        for (step in listOf(
            SubmissionProgress.Step.CreatingObservation,
            SubmissionProgress.Step.UploadingPrimaryPhoto,
            SubmissionProgress.Step.UploadingExtraPhoto,
            SubmissionProgress.Step.ApplyingTag,
            SubmissionProgress.Step.Persisting,
        )) {
            assertThat(photoProgressRowState(terminal, null, step)).isEqualTo(ProgressRowState.Done)
        }
    }

    @Test
    fun `photoProgressRowState failure at first step marks only that row Failed all others Pending`() {
        val failedAt = SubmissionProgress.Step.CreatingObservation
        val terminal = SubmissionProgress.Step.DoneFailed

        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.CreatingObservation)
        ).isEqualTo(ProgressRowState.Failed)
        assertThat(
            photoProgressRowState(terminal, failedAt, SubmissionProgress.Step.UploadingPrimaryPhoto)
        ).isEqualTo(ProgressRowState.Pending)
    }

    private fun suggestion(id: Long, name: String) = PhotoVisionSuggestion(
        taxonId = id,
        scientificName = name,
        commonName = null,
        rank = "species",
        rankLevel = 10,
        score = id.toDouble(),
        iconicTaxonName = "Insecta",
    )
}
