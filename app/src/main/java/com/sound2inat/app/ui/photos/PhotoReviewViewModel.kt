package com.sound2inat.app.ui.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.storage.PhotoDraftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotoReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: PhotoDraftRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        repo: PhotoDraftRepository,
    ) : this(savedStateHandle, repo, null)

    private val draftId = checkNotNull(savedStateHandle.get<String>("photoDraftId")) {
        "photoDraftId is required"
    }
    private val scope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(PhotoReviewUiState(draftId = draftId))
    val state: StateFlow<PhotoReviewUiState> = _state

    init {
        scope.launch {
            repo.observeWithImages(draftId)
                .catch { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                .collect { withImages ->
                    if (withImages == null) {
                        _state.value = PhotoReviewUiState(draftId = draftId, isLoading = false)
                    } else {
                        val draft = withImages.draft
                        _state.value = PhotoReviewUiState(
                            draftId = draftId,
                            isLoading = false,
                            images = withImages.images,
                            taxonScientificName = draft.taxonScientificName,
                            taxonCommonName = draft.taxonCommonName,
                            taxonInatId = draft.taxonInatId,
                            description = draft.description,
                        )
                    }
                }
        }
    }

    suspend fun deleteImage(imageId: String) {
        repo.deleteImage(imageId)
    }

    suspend fun deleteAlbum() {
        repo.deleteDraft(draftId)
    }

    suspend fun saveDetails(
        taxonScientificName: String?,
        taxonCommonName: String?,
        taxonInatId: Long?,
        description: String?,
    ) {
        repo.updateDetails(
            draftId = draftId,
            taxonScientificName = taxonScientificName?.trim()?.takeIf { it.isNotEmpty() },
            taxonCommonName = taxonCommonName?.trim()?.takeIf { it.isNotEmpty() },
            taxonInatId = taxonInatId,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
