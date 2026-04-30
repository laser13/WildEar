package com.sound2inat.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.inat.INaturalistClient
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
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Lightweight projection of a Room [com.sound2inat.storage.DetectionEntity] for
 * the Home recording-card preview row. Keeps just the fields the UI needs and
 * spares the screen from depending on the storage entity directly.
 */
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
    private val inatClient: INaturalistClient,
) : ViewModel() {

    val delegate = HomeViewModel(
        observeDrafts = { repo.observeAll() },
        topLabelFor = { _ ->
            // first detection (sorted by maxConfidence desc); commonName preferred over scientific
            null // computed per-row in the screen using detectionDao.observeForDraft(id)
        },
        isModelReady = { modelManager.stateFor(BirdNetV24.descriptor) is ModelInstallState.Ready },
    )

    /**
     * Lazy in-memory cache of taxon photo URLs keyed by scientific name. Shared
     * across all recording cards so opening the Home screen doesn't fan out
     * one network call per card per species. Lives for the VM lifetime, which
     * is long enough to cover routine list-scrolling.
     */
    private val photoFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    fun observeTopLabel(draftId: String): Flow<String?> =
        detectionDao.observeForDraft(draftId).map { list ->
            list.firstOrNull()?.let { it.taxonCommonName ?: it.taxonScientificName }
        }.flowOn(Dispatchers.IO)

    /**
     * Top [limit] detected species for a draft, ordered by confidence (the
     * underlying DAO query already sorts that way). Used by the recording
     * card to show a small avatar row of what the analysis found.
     */
    fun observeTopSpecies(draftId: String, limit: Int = TOP_SPECIES_LIMIT): Flow<List<TopSpeciesItem>> =
        detectionDao.observeForDraft(draftId)
            .map { list ->
                list.take(limit).map { d -> TopSpeciesItem(d.taxonScientificName, d.taxonCommonName) }
            }
            .flowOn(Dispatchers.IO)

    /**
     * Cached resolution of an iNaturalist taxon photo URL. First call for a
     * given name kicks off a fetch; subsequent calls return the same flow,
     * which emits null until the network round-trip completes (or stays null
     * if the taxon has no default photo). Errors are swallowed — a missing
     * thumbnail is not a UX-blocking problem.
     */
    fun observeTaxonPhoto(name: String): StateFlow<String?> {
        photoFlows[name]?.let { return it }
        val flow = MutableStateFlow<String?>(null)
        val prev = photoFlows.putIfAbsent(name, flow)
        if (prev != null) return prev
        viewModelScope.launch {
            val url = runCatching {
                withContext(Dispatchers.IO) { inatClient.fetchTaxonPhotoUrl(name) }
            }.getOrNull()
            flow.value = url
        }
        return flow
    }

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

    private companion object {
        const val TOP_SPECIES_LIMIT = 3
    }
}
