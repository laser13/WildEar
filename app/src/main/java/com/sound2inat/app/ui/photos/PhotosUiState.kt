package com.sound2inat.app.ui.photos

import com.sound2inat.storage.PhotoDraftSummary

data class PhotosUiState(
    val drafts: List<PhotoDraftSummary> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)
