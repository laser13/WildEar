package com.sound2inat.app.ui.photos

import com.sound2inat.inat.ObservationComment
import com.sound2inat.inat.PhotoVisionLadder
import com.sound2inat.inat.PhotoVisionSuggestion
import com.sound2inat.inat.SubmissionProgress
import com.sound2inat.storage.PhotoDraftImageEntity

/**
 * Snapshot of the Photo Review screen state.
 *
 * ### Uploaded-observation fields
 *
 * There are three related fields that together describe whether and how an
 * observation has been submitted to iNaturalist:
 *
 * - [inatObservationId] — numeric iNaturalist observation ID, sourced from Room
 *   (persisted). Present once the server confirms creation and the DB has been
 *   updated. This is the canonical "uploaded" indicator.
 * - [inatObservationUrl] — full observation URL, also sourced from Room.
 *   Mirrors [inatObservationId]: both arrive together from the same DB row.
 * - [uploadedUrl] — **optimistic** URL set immediately in the ViewModel after
 *   a successful upload HTTP response, *before* Room emits the updated draft.
 *   Acts as a short-lived placeholder so the UI can show the observation link
 *   without waiting for the next Room emission. Once Room emits, [inatObservationUrl]
 *   takes over and [uploadedUrl] becomes redundant (but harmless).
 *
 * Use [isUploaded] (checks all three) or [observationUrl] (prefers Room URL)
 * rather than reading these fields directly.
 */
data class PhotoReviewUiState(
    val draftId: String = "",
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val images: List<PhotoDraftImageEntity> = emptyList(),
    val observedAtUtcMs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val inatObservationId: Long? = null,
    val inatObservationUuid: String? = null,
    val inatObservationUrl: String? = null,
    val taxonScientificName: String? = null,
    val taxonCommonName: String? = null,
    val taxonInatId: Long? = null,
    val description: String? = null,
    val error: String? = null,
    val submitError: String? = null,
    val uploadedUrl: String? = null,
    val isSyncingObservation: Boolean = false,
    val syncError: String? = null,
    val observationDetail: PhotoObservationDetailUiState? = null,
    val vision: PhotoVisionPanelUiState = PhotoVisionPanelUiState(),
    val submissionProgress: SubmissionProgress? = null,
    val incompleteObservation: IncompletePhotoObservationUi? = null,
    val retryingIncomplete: Boolean = false,
    val retryIncompleteError: String? = null,
)

data class IncompletePhotoObservationUi(
    val observationId: Long,
    val scientificName: String,
    val url: String,
)

data class PhotoObservationDetailUiState(
    val qualityGrade: String,
    val agreeingIdCount: Int,
    val commentsCount: Int,
    val comments: List<ObservationComment> = emptyList(),
    val taxonScientificName: String? = null,
    val taxonCommonName: String? = null,
)

data class PhotoVisionPanelUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val ladder: PhotoVisionLadder? = null,
)

enum class PhotoCropMode { Original, Square }

data class PhotoCropRequest(
    val frameSizePx: Int,
    val frameHeightPx: Int = frameSizePx,
    val viewportWidthPx: Int = frameSizePx,
    val viewportHeightPx: Int = frameHeightPx,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotationDegrees: Int = 0,
    val cropMode: PhotoCropMode = PhotoCropMode.Square,
)

val PhotoReviewUiState.isUploaded: Boolean
    get() = incompleteObservation == null &&
        (inatObservationId != null || inatObservationUrl != null || uploadedUrl != null)

val PhotoReviewUiState.observationUrl: String?
    get() = inatObservationUrl ?: uploadedUrl

val PhotoReviewUiState.canRunVision: Boolean
    get() = inatObservationId != null && !vision.isLoading

val PhotoReviewUiState.isUploading: Boolean
    get() = isSubmitting && !isUploaded

val PhotoReviewUiState.resolvedObservationId: Long?
    get() = inatObservationId ?: observationIdFromUrl(observationUrl)

data class PhotoRankActionUi(
    val label: String,
    val suggestion: PhotoVisionSuggestion,
)

fun PhotoVisionLadder.availableRankActions(): List<PhotoRankActionUi> = buildList {
    higherTaxa.firstOrNull { it.rank == "genus" }
        ?.let { add(PhotoRankActionUi("Use genus only", it)) }
    higherTaxa.firstOrNull { it.rank == "family" }
        ?.let { add(PhotoRankActionUi("Use family only", it)) }
}

fun PhotoVisionLadder.visibleSuggestions(showAll: Boolean): List<PhotoVisionSuggestion> =
    if (showAll || topCandidates.size <= 3) topCandidates else topCandidates.take(3)

fun PhotoVisionLadder.shouldShowAllToggle(): Boolean = topCandidates.size > 2

private fun observationIdFromUrl(url: String?): Long? =
    url?.trim()?.trimEnd('/')?.substringAfterLast('/')?.toLongOrNull()
