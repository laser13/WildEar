package com.sound2inat.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.app.inference.InferenceQueue
import com.sound2inat.inat.TaxonPhotoRepository
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.inference.SourceStats
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DetectionDao
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: DraftRepository,
    private val detectionDao: DetectionDao,
    private val inatObservationDao: InatObservationDao,
    private val modelManager: ModelManager,
    private val taxonPhotoRepository: TaxonPhotoRepository,
    private val settings: Settings,
    private val inferenceQueue: InferenceQueue,
) : ViewModel() {

    private val readyState = MutableStateFlow(false)

    init { refreshModelState() }

    fun refreshModelState() {
        viewModelScope.launch {
            readyState.value = modelManager.stateFor(BirdNetV24.descriptor) is ModelInstallState.Ready
        }
    }

    val state: StateFlow<HomeUiState> = combine(repo.observeAll(), readyState) { drafts, ready ->
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

    val filterMode = MutableStateFlow(FilterMode.ALL)

    val allowDeleteUploaded: StateFlow<Boolean> = settings.allowDeleteUploaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    // Enriched state: draft list + per-draft counts + live queue status joined into DraftSummary.
    // Used for filtering, bulk delete decisions, and queue status display.
    val enrichedDrafts: StateFlow<List<DraftSummary>> = combine(
        state,
        inatObservationDao.observeCountsByDraft(),
        detectionDao.observeCountsByDraft(),
        inferenceQueue.status,
    ) { homeState, inatCounts, detectionCounts, queueStatus ->
        val inatMap = inatCounts.associate { it.draftId to it.count }
        val detMap = detectionCounts.associate { it.draftId to it.count }
        homeState.drafts.map { d ->
            d.copy(
                inatCount = inatMap[d.id] ?: 0,
                detectionCount = detMap[d.id] ?: 0,
                jobStatus = queueStatus[d.id],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val filteredDrafts: StateFlow<List<DraftSummary>> = combine(
        enrichedDrafts,
        filterMode,
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

    fun observeRecordingSpecies(draftId: String): Flow<List<RecordingSpeciesItem>> =
        detectionDao.observeForDraft(draftId)
            .map { list ->
                sortRecordingSpecies(
                    list.map { d ->
                        RecordingSpeciesItem(
                            scientificName = d.taxonScientificName,
                            commonName = d.taxonCommonName,
                            maxConfidence = d.maxConfidence,
                            regionalStatus = d.regionalStatus?.let {
                                runCatching { RegionalStatus.valueOf(it) }.getOrNull()
                            },
                        )
                    },
                )
            }
            .flowOn(Dispatchers.IO)

    fun observeRecordingModels(draftId: String): Flow<Set<ModelBadge>> =
        detectionDao.observeForDraft(draftId)
            .map { list ->
                val keys = buildSet {
                    list.forEach { addAll(SourceStats.decodeConfidenceOnly(it.sources).keys) }
                }
                modelBadgesOf(keys)
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class RecordingSpeciesItem(
    val scientificName: String,
    val commonName: String?,
    val maxConfidence: Float,
    val regionalStatus: RegionalStatus?,
)

enum class ModelBadge { BIRDNET, PERCH }

internal fun sortRecordingSpecies(items: List<RecordingSpeciesItem>): List<RecordingSpeciesItem> {
    // NOT_CONFIRMED goes to the end; everything else (CONFIRMED / UNVERIFIED / null) shown first.
    // Within each group, score DESC. sortedWith is stable for equal keys.
    return items.sortedWith(
        compareBy<RecordingSpeciesItem> { it.regionalStatus == RegionalStatus.NOT_CONFIRMED }
            .thenByDescending { it.maxConfidence },
    )
}

internal fun modelBadgesOf(sourceKeys: Set<String>): Set<ModelBadge> = buildSet {
    sourceKeys.forEach { key ->
        when {
            key.startsWith("birdnet") -> add(ModelBadge.BIRDNET)
            key.startsWith("perch") -> add(ModelBadge.PERCH)
        }
    }
}
