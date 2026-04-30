package com.sound2inat.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.InatObservationDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class HomeViewModel(
    private val observeDrafts: () -> Flow<List<DraftEntity>>,
    private val topLabelFor: suspend (String) -> String?,
    private val isModelReady: suspend () -> Boolean,
) : ViewModel() {

    private val readyState = MutableStateFlow(false)

    init { refreshModelState() }

    fun refreshModelState() {
        viewModelScope.launch { readyState.value = isModelReady() }
    }

    val state: StateFlow<HomeUiState> = combine(observeDrafts(), readyState) { drafts, ready ->
        HomeUiState(
            isModelReady = ready,
            drafts = drafts.map { d ->
                DraftSummary(
                    id = d.id,
                    recordedAtUtcMs = d.recordedAtUtcMs,
                    durationMs = d.durationMs,
                    status = d.status,
                    topLabel = null, // resolved in the screen via per-row Flow
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private companion object { const val STOP_TIMEOUT_MS = 5_000L }
}

@HiltViewModel
class HomeViewModelHilt @Inject constructor(
    private val repo: DraftRepository,
    private val detectionDao: DetectionDao,
    private val inatObservationDao: InatObservationDao,
    private val modelManager: ModelManager,
) : ViewModel() {

    val delegate = HomeViewModel(
        observeDrafts = { repo.observeAll() },
        topLabelFor = { _ ->
            // first detection (sorted by maxConfidence desc); commonName preferred over scientific
            null // computed per-row in the screen using detectionDao.observeForDraft(id)
        },
        isModelReady = { modelManager.stateFor(BirdNetV24.descriptor) is ModelInstallState.Ready },
    )

    fun observeTopLabel(draftId: String): Flow<String?> =
        detectionDao.observeForDraft(draftId).map { list ->
            list.firstOrNull()?.let { it.taxonCommonName ?: it.taxonScientificName }
        }.flowOn(Dispatchers.IO)

    /**
     * Number of iNaturalist observations already uploaded for this draft.
     * Surface a "✓ N on iNaturalist" hint per card on the Home screen — the
     * user can tell at a glance which recordings have been pushed and how
     * many species ended up published.
     */
    fun observeInatObservationCount(draftId: String): Flow<Int> =
        inatObservationDao.observeForDraft(draftId)
            .map { it.size }
            .flowOn(Dispatchers.IO)

    fun refreshModelState() { delegate.refreshModelState() }
}
