package com.sound2inat.app.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.inat.PhotoObservationSyncUseCase
import com.sound2inat.storage.PhotoDraftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel(
    private val repo: PhotoDraftRepository,
    private val syncUseCase: PhotoObservationSyncUseCase,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(
        repo: PhotoDraftRepository,
        syncUseCase: PhotoObservationSyncUseCase,
    ) : this(repo, syncUseCase, null)

    private val _state = MutableStateFlow(PhotosUiState())
    val state: StateFlow<PhotosUiState> = _state.asStateFlow()
    private val scope = externalScope ?: viewModelScope
    private val refreshInFlight = AtomicBoolean(false)

    init {
        scope.launch {
            repo.observeSummaries()
                .catch { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                .collect { drafts ->
                    // update {} (not value = PhotosUiState(...)) so an in-flight refresh's
                    // isRefreshing/lastSyncResult survive the Room emissions the sync triggers.
                    _state.update { it.copy(drafts = drafts, isLoading = false, error = null) }
                }
        }
    }

    fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        _state.update { it.copy(isRefreshing = true) }
        scope.launch {
            try {
                val targets = _state.value.drafts.mapNotNull { d ->
                    d.inatObservationId?.let { d.id to it }
                }
                val result = syncUseCase.syncAll(targets)
                _state.update { it.copy(lastSyncResult = result) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
                refreshInFlight.set(false)
            }
        }
    }

    fun clearSyncResult() {
        _state.update { it.copy(lastSyncResult = null) }
    }
}
