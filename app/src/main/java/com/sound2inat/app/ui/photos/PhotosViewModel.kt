package com.sound2inat.app.ui.photos

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
class PhotosViewModel(
    private val repo: PhotoDraftRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(repo: PhotoDraftRepository) : this(repo, null)

    private val _state = MutableStateFlow(PhotosUiState())
    val state: StateFlow<PhotosUiState> = _state
    private val scope = externalScope ?: viewModelScope

    init {
        start(scope)
    }

    private fun start(scope: CoroutineScope) {
        scope.launch {
            repo.observeSummaries()
                .catch { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                .collect { drafts ->
                    _state.value = PhotosUiState(drafts = drafts, isLoading = false)
                }
        }
    }
}
