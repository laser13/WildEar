package com.sound2inat.app.ui.photos

import com.sound2inat.storage.PhotoDraftImageEntity

data class PhotoReviewUiState(
    val draftId: String = "",
    val isLoading: Boolean = true,
    val images: List<PhotoDraftImageEntity> = emptyList(),
    val taxonScientificName: String? = null,
    val taxonCommonName: String? = null,
    val taxonInatId: Long? = null,
    val description: String? = null,
    val error: String? = null,
) {
    val selectedImagePath: String? = images.firstOrNull()?.photoPath
}
