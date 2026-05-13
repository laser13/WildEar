package com.sound2inat.app.ui.photos

import com.sound2inat.storage.PhotoDraftImageEntity

data class PhotoReviewUiState(
    val draftId: String = "",
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val images: List<PhotoDraftImageEntity> = emptyList(),
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
    val vision: PhotoVisionPanelUiState = PhotoVisionPanelUiState(),
)

data class PhotoVisionPanelUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val ladder: PhotoVisionLadder? = null,
)
