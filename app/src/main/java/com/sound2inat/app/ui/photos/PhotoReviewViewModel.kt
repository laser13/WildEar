package com.sound2inat.app.ui.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.PhotoSubmitResult
import com.sound2inat.inat.PhotoSubmitter
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
    private val auth: INatAuthRepository,
    private val submitter: PhotoSubmitter,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        repo: PhotoDraftRepository,
        auth: INatAuthRepository,
        submitter: PhotoSubmitter,
    ) : this(
        savedStateHandle = savedStateHandle,
        repo = repo,
        auth = auth,
        submitter = submitter,
        externalScope = null,
    )

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
                        _state.update { current ->
                            current.copy(
                                draftId = draftId,
                                isLoading = false,
                                images = emptyList(),
                            )
                        }
                    } else {
                        val draft = withImages.draft
                        _state.update { current ->
                            current.copy(
                                draftId = draftId,
                                isLoading = false,
                                images = withImages.images,
                                taxonScientificName = draft.taxonScientificName,
                                taxonCommonName = draft.taxonCommonName,
                                taxonInatId = draft.taxonInatId,
                                description = draft.description,
                                submitError = draft.inatLastError ?: current.submitError,
                                uploadedUrl = draft.inatObservationUrl ?: current.uploadedUrl,
                            )
                        }
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

    fun submit() {
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        scope.launch {
            val token = auth.getValidToken().orEmpty()
            when (val result = submitter.submit(token, draftId)) {
                is PhotoSubmitResult.Ok -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            uploadedUrl = result.observationUrl,
                            submitError = result.warnings.joinToString(" | ").takeIf { text -> text.isNotBlank() },
                        )
                    }
                }
                is PhotoSubmitResult.Failure -> {
                    _state.update { it.copy(isSubmitting = false, submitError = result.message) }
                }
            }
        }
    }
}
