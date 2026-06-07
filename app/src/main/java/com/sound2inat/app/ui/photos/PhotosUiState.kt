package com.sound2inat.app.ui.photos

import com.sound2inat.inat.PhotoSyncResult
import com.sound2inat.storage.PhotoDraftSummary

data class PhotosUiState(
    val drafts: List<PhotoDraftSummary> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    /** One-shot result of the last pull-to-refresh; cleared by the screen after it is shown. */
    val lastSyncResult: PhotoSyncResult? = null,
)
