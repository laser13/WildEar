package com.sound2inat.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.TaxonPhotoRepository
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
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
                    topLabel = null,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private companion object { const val STOP_TIMEOUT_MS = 5_000L }
}

data class TopSpeciesItem(
    val scientificName: String,
    val commonName: String?,
)

@HiltViewModel
class HomeViewModelHilt @Inject constructor(
    private val repo: DraftRepository,
    private val detectionDao: DetectionDao,
    private val inatObservationDao: InatObservationDao,
    private val modelManager: ModelManager,
    private val taxonPhotoRepository: TaxonPhotoRepository,
    private val settings: Settings,
) : ViewModel() {

    val delegate = HomeViewModel(
        observeDrafts = { repo.observeAll() },
        topLabelFor = { _ -> null },
        isModelReady = { modelManager.stateFor(BirdNetV24.descriptor) is ModelInstallState.Ready },
    )

    val filterMode = MutableStateFlow(FilterMode.ALL)

    val allowDeleteUploaded: StateFlow<Boolean> = settings.allowDeleteUploaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    // Enriched state: draft list + per-draft counts joined into DraftSummary.
    // Used for filtering and bulk delete decisions.
    val enrichedDrafts: StateFlow<List<DraftSummary>> = combine(
        delegate.state,
        inatObservationDao.observeCountsByDraft(),
        detectionDao.observeCountsByDraft(),
    ) { homeState, inatCounts, detectionCounts ->
        val inatMap = inatCounts.associate { it.draftId to it.count }
        val detMap = detectionCounts.associate { it.draftId to it.count }
        homeState.drafts.map { d ->
            d.copy(
                inatCount = inatMap[d.id] ?: 0,
                detectionCount = detMap[d.id] ?: 0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val filteredDrafts: StateFlow<List<DraftSummary>> = combine(
        enrichedDrafts, filterMode,
    ) { drafts, mode ->
        when (mode) {
            FilterMode.ALL -> drafts
            FilterMode.UPLOADED -> drafts.filter { it.inatCount > 0 || it.status == DraftStatus.UPLOADED }
            FilterMode.NOTHING_DETECTED -> drafts.filter {
                it.detectionCount == 0 &&
                    (it.status == DraftStatus.PENDING_REVIEW || it.status == DraftStatus.REVIEWED)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setFilterMode(mode: FilterMode) {
        filterMode.value = mode
        selectedIds.value = emptySet()
    }

    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    fun toggleSelection(id: String) {
        val cur = selectedIds.value
        selectedIds.value = if (id in cur) cur - id else cur + id
    }

    fun selectAllVisible() {
        selectedIds.value = filteredDrafts.value.map { it.id }.toSet()
    }

    fun clearSelection() { selectedIds.value = emptySet() }

    fun previewSelectedDelete(): BulkDeletePreview {
        val ids = selectedIds.value
        val drafts = filteredDrafts.value.filter { it.id in ids }
        val allowUploaded = allowDeleteUploaded.value
        val toDelete = drafts.filter { allowUploaded || (it.inatCount == 0 && it.status != DraftStatus.UPLOADED) }
        val skipped = drafts.size - toDelete.size
        return BulkDeletePreview(toDelete, skipped)
    }

    fun bulkDelete(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { repo.delete(it) }
            selectedIds.value = emptySet()
        }
    }

    fun observeTopLabel(draftId: String): Flow<String?> =
        detectionDao.observeForDraft(draftId).map { list ->
            list.firstOrNull()?.let { it.taxonCommonName ?: it.taxonScientificName }
        }.flowOn(Dispatchers.IO)

    fun observeTopSpecies(draftId: String, limit: Int = TOP_SPECIES_LIMIT): Flow<List<TopSpeciesItem>> =
        detectionDao.observeForDraft(draftId)
            .map { list ->
                list.take(limit).map { d -> TopSpeciesItem(d.taxonScientificName, d.taxonCommonName) }
            }
            .flowOn(Dispatchers.IO)

    fun observeDetectionCount(draftId: String): Flow<Int> =
        detectionDao.observeForDraft(draftId)
            .map { it.size }
            .flowOn(Dispatchers.IO)

    fun observeTaxonPhoto(name: String): StateFlow<String?> =
        taxonPhotoRepository.observe(name)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun observeInatObservationCount(draftId: String): Flow<Int> =
        inatObservationDao.observeForDraft(draftId)
            .map { it.size }
            .flowOn(Dispatchers.IO)

    fun refreshModelState() { delegate.refreshModelState() }

    private companion object {
        const val TOP_SPECIES_LIMIT = 3
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
