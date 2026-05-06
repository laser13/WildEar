package com.sound2inat.app.ui.review

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Singleton
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatSubmitter
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.ObservationDetail
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.FragmentRanges
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.BirdNetMetaModel
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.InferenceRunner
import com.sound2inat.inference.SourceConfidences
import com.sound2inat.inference.SpectralSubtractor
import com.sound2inat.inference.WavReader
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.inference.YamNetGate
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.InatObservationDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/**
 * Result of a single inference invocation. Either [Success] with the
 * aggregated species list, or [Failure] with a user-facing message.
 */
sealed interface InferenceOutcome {
    data class Success(
        val modelId: String,
        val modelVersion: String,
        val detections: List<AggregatedDetection>,
        /**
         * Raw per-window predictions retained for overlay rendering on the
         * Review screen. Not persisted — overlays disappear if the user closes
         * and reopens the screen until inference reruns. (See Task 15.)
         */
        val windows: List<WindowPrediction> = emptyList(),
    ) : InferenceOutcome

    data class Failure(val message: String) : InferenceOutcome
}

/**
 * Builds (or loads cached) waveform and spectrogram artifacts for a draft.
 * Decoupled from Android's `Bitmap` so the VM can be unit-tested on the JVM.
 *
 * Production wiring: [ProductionVisualsProvider] reads the WAV via
 * [WavReader.readMono16], runs [SpectrogramRenderer], persists the PNG via
 * [SpectrogramBitmap.writePng], and computes per-column peaks via
 * [WaveformBitmap.peaks].
 */
fun interface VisualsProvider {
    suspend fun build(audioPath: String, draftId: String, filesDir: File): Visuals
}

/**
 * Output of [VisualsProvider]. [spectrogramFile] points at a PNG cached on
 * disk; [waveformPeaks] is the interleaved (min, max) envelope used by the
 * Compose waveform canvas.
 */
data class Visuals(
    val spectrogramFile: File,
    val waveformPeaks: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Visuals) return false
        return spectrogramFile == other.spectrogramFile &&
            waveformPeaks.contentEquals(other.waveformPeaks)
    }

    override fun hashCode(): Int =
        spectrogramFile.hashCode() * HASH_PRIME + waveformPeaks.contentHashCode()

    companion object {
        private const val HASH_PRIME = 31
    }
}

/**
 * Runs inference for a single draft. Implementations are responsible for:
 * - resolving the on-disk model & labels,
 * - loading the model,
 * - slicing the WAV and reporting progress via [onProgress] in `[0,1]`,
 * - aggregating predictions into a species list.
 *
 * Decoupled from [BioacousticModel] / [InferenceRunner] so the VM is testable
 * without TFLite or a real WAV.
 */
fun interface InferenceJob {
    suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): InferenceOutcome
}

/**
 * Submission seam abstracted from the production [INatSubmitter] so the VM
 * is unit-testable without going near OkHttp. Test fakes return canned
 * outcomes; the production wiring forwards to [INatSubmitter.submit].
 */
fun interface InatSubmissionJob {
    suspend fun submit(token: String, draftId: String): InatSubmissionOutcome
}

/**
 * Outcome of an on-demand Perch analysis pass over a saved WAV. The VM merges
 * [Success.detections] with the existing per-draft detections and persists the
 * union — Perch never *replaces* prior BirdNET output.
 */
sealed interface PerchAnalysisOutcome {
    /** Aggregated Perch detections, ready to be merged. */
    data class Success(val detections: List<AggregatedDetection>) : PerchAnalysisOutcome

    /** Perch model artifact is not installed (state != Ready) — UI shouldn't have offered the button. */
    data object NotInstalled : PerchAnalysisOutcome

    data class Failure(val message: String) : PerchAnalysisOutcome
}

/**
 * Seam abstracting the on-demand Perch pipeline from the VM so unit tests can
 * inject canned outcomes without loading a 407 MB TFLite. Production wiring
 * loads [com.sound2inat.inference.PerchTfliteModel] and runs
 * [com.sound2inat.inference.InferenceRunner] against the WAV at [audioPath].
 */
fun interface PerchAnalysisJob {
    suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): PerchAnalysisOutcome
}

sealed interface InatSubmissionOutcome {
    data class Success(val urls: List<String>) : InatSubmissionOutcome
    data class Failure(val message: String) : InatSubmissionOutcome
}

@Suppress("LongParameterList")
class ReviewViewModel(
    private val draftId: String,
    private val repo: DraftRepository,
    private val player: AudioPlayer,
    private val inference: InferenceJob,
    private val visuals: VisualsProvider = NoopVisualsProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val submission: InatSubmissionJob = InatSubmissionJob { _, _ ->
        InatSubmissionOutcome.Failure("No iNaturalist submitter configured")
    },
    private val tokenProvider: suspend () -> String? = { null },
    private val inatObservationsFlow: kotlinx.coroutines.flow.Flow<List<Pair<String, String>>> =
        kotlinx.coroutines.flow.flowOf(emptyList()),
    /** Returns the iNaturalist default photo medium_url for a scientific name, or null. */
    private val photoFetcher: suspend (String) -> String? = { null },
    private val perchAnalysis: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ ->
        PerchAnalysisOutcome.NotInstalled
    },
    /**
     * Returns true if the Perch model is installed (Ready) right now. Queried
     * once on init and after every successful Perch run; the UI gates the
     * "Analyze with Perch" button on this AND the absence of any
     * `perch_v2`-sourced detection on the draft.
     */
    private val perchInstalledProbe: suspend () -> Boolean = { false },
    /**
     * Optional regional annotation engine. When null, [launchAnnotation] is a
     * no-op and [SpeciesRow.regionalStatus] stays null for all rows.
     */
    private val regionFilter: RegionFilter? = null,
    /**
     * Returns the current `minWindows` threshold. Injected as a lambda so the
     * VM is testable without an Android [Settings] instance.
     */
    private val minWindowsProvider: suspend () -> Int = { 1 },
    private val regionRadiusKmProvider: suspend () -> Int = { Settings.DEFAULT_REGION_RADIUS_KM },
    private val observationFetcher: suspend (idOrUuid: String) -> ObservationDetail = {
        throw com.sound2inat.inat.INatException(-1, "No observation fetcher configured")
    },
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val ownsScope: Boolean = externalScope == null
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(ReviewUiState(draftId = draftId))
    val state: StateFlow<ReviewUiState> = _state

    private val _spectrogramFile = MutableStateFlow<File?>(null)
    val spectrogramFile: StateFlow<File?> = _spectrogramFile

    private val _waveformPeaks = MutableStateFlow<FloatArray?>(null)
    val waveformPeaks: StateFlow<FloatArray?> = _waveformPeaks

    /** Cached denoise-preview WAV; null until the user enables the toggle and the build finishes. */
    private val _denoisedAudioFile = MutableStateFlow<File?>(null)
    val denoisedAudioFile: StateFlow<File?> = _denoisedAudioFile

    /** Cached denoise-preview spectrogram PNG; null until built. */
    private val _denoisedSpectrogramFile = MutableStateFlow<File?>(null)
    val denoisedSpectrogramFile: StateFlow<File?> = _denoisedSpectrogramFile

    /**
     * Raw per-window predictions surfaced from the latest inference run, kept in
     * memory only for the lifetime of the screen — see [InferenceOutcome.Success.windows].
     */
    private val _windowPreds = MutableStateFlow<List<WindowPrediction>>(emptyList())
    val windowPreds: StateFlow<List<WindowPrediction>> = _windowPreds

    /**
     * Currently flashing/selected detection id, or `null` when no row is
     * highlighted. Auto-clears [HighlightDurationMs] ms after being set.
     */
    private val _highlight = MutableStateFlow<Long?>(null)
    val highlight: StateFlow<Long?> = _highlight

    private var highlightJob: Job? = null

    /**
     * Long-lived cache `taxonScientificName → photoUrl`. Survives Room
     * re-emissions (including the brief empty list emitted between
     * `deleteForDraft` and `insertAll` inside [DraftRepository.attachDetections],
     * which used to wipe the URLs because `prevPhotos` was rebuilt from
     * `_state.value.species` — a snapshot that could already be empty).
     */
    private val photoUrlCache: MutableMap<String, String?> = mutableMapOf()

    /**
     * Long-lived cache `taxonScientificName → RegionalStatus`. Populated by
     * [launchAnnotation] so re-emissions from Room preserve already-fetched
     * statuses rather than resetting them to null.
     */
    private val regionalStatusCache: MutableMap<String, RegionalStatus?> = mutableMapOf()
    private val observationDetailCache = mutableMapOf<String, ObservationDetail?>()
    private var annotationJob: Job? = null

    /** Latest playback position from the player; ticks every 50 ms during playback. */
    val playerPosition: StateFlow<Long> = player.position

    private var inferenceStarted = false
    private var inferenceJob: Job? = null
    private var visualsStarted = false
    private var visualsJob: Job? = null

    /** Last result of [perchInstalledProbe]; folded into [ReviewUiState.canAnalyzeWithPerch]. */
    private var perchInstalled: Boolean = false
    private var perchJob: Job? = null

    /**
     * Serialises [mergeAndPersist] across BirdNET and Perch jobs. Without this,
     * a quick second analysis trigger can race the in-flight read-merge-write
     * cycle and clobber the other job's results.
     */
    private val persistMutex = Mutex()

    init {
        scope.launch {
            // Probe once on init — the user can install Perch later, but the
            // typical flow is: install in Settings -> open a draft. Probing
            // again after each successful analyzeWithPerch run lets us flip the
            // button off (we just produced perch detections).
            perchInstalled = runCatching { perchInstalledProbe() }.getOrDefault(false)
            recomputePerchEligibility()
        }
        scope.launch {
            inatObservationsFlow.collect { rows ->
                _state.value = _state.value.copy(inatObservations = rows)
            }
        }
        // Lazily fetch iNat taxon photos for each species as the list is populated.
        // distinctUntilChanged on names avoids re-fetching on playback-position updates.
        scope.launch {
            var fetched = emptySet<String>()
            _state
                .map { s -> s.species.map { it.taxonScientificName } }
                .distinctUntilChanged()
                .collect { names ->
                    val newNames = names.toSet() - fetched
                    if (newNames.isEmpty()) return@collect
                    fetched = fetched + newNames
                    for (name in newNames) {
                        scope.launch {
                            val url = runCatching { photoFetcher(name) }.getOrNull()
                            photoUrlCache[name] = url
                            _state.update { cur ->
                                cur.copy(
                                    species = cur.species.map { row ->
                                        if (row.taxonScientificName == name) {
                                            row.copy(taxonPhotoUrl = url)
                                        } else {
                                            row
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
        }
        scope.launch {
            repo.observeWithDetections(draftId).collect { dwd ->
                val draft = dwd.draft
                // Preserve already-fetched taxonPhotoUrl across DB re-emissions.
                // Reads from the long-lived [photoUrlCache] rather than
                // _state.value.species — DraftRepository.attachDetections
                // (delete + insert without @Transaction) emits an empty list
                // between the two writes, which would wipe a snapshot map.
                val minWin = minWindowsProvider()
                val prevDetailStates = _state.value.species.associate { it.detectionId to it.observationDetailState }
                val rows = dwd.detections
                    .filter { e -> e.detectedWindows >= minWin }
                    .map { e ->
                        SpeciesRow(
                            detectionId = e.id,
                            taxonScientificName = e.taxonScientificName,
                            taxonCommonName = e.taxonCommonName,
                            maxConfidence = e.maxConfidence,
                            detectedWindows = e.detectedWindows,
                            firstSeenMs = e.firstSeenMs,
                            lastSeenMs = e.lastSeenMs,
                            isSelected = e.isSelectedByUser,
                            confidenceBySource = SourceConfidences.decode(e.sources),
                            taxonPhotoUrl = photoUrlCache[e.taxonScientificName],
                            regionalStatus = regionalStatusCache[e.taxonScientificName],
                            observationDetailState = prevDetailStates[e.id] ?: ObservationDetailLoadState.NotLoaded,
                            fragmentRanges = FragmentRanges.decode(e.fragmentRanges),
                        )
                    }
                _state.value = _state.value.copy(
                    status = draft.status,
                    recordedAtUtcMs = draft.recordedAtUtcMs,
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    durationMs = draft.durationMs,
                    audioPath = draft.audioPath,
                    species = rows,
                )
                val lat = draft.latitude
                val lon = draft.longitude
                if (lat != null && lon != null) {
                    val newNames = rows.map { it.taxonScientificName }.toSet()
                    val cachedNames = regionalStatusCache.keys.toSet()
                    // Skip annotation while inference is pending — launchAnnotationIfIdle()
                    // will run it on the final merged data after analysis completes.
                    // Also ignore the transient empty emission from Room's non-atomic
                    // DELETE+INSERT in attachDetections.
                    val inferenceRunning = draft.status == DraftStatus.PENDING_INFERENCE ||
                        _state.value.inferenceProgress != null ||
                        _state.value.perchProgress != null
                    if (newNames != cachedNames && newNames.isNotEmpty() && !inferenceRunning) {
                        Log.d("ReviewVM", "annotation triggered: newNames=$newNames cachedNames=$cachedNames")
                        launchAnnotation(rows, lat, lon)
                    } else {
                        Log.d("ReviewVM", "annotation skipped (inference=$inferenceRunning empty=${newNames.isEmpty()} same=${newNames == cachedNames})")
                    }
                } else {
                    Log.d("ReviewVM", "annotation skipped: lat=$lat lon=$lon (no GPS)")
                }
                recomputePerchEligibility()
                if (draft.status == DraftStatus.PENDING_INFERENCE && !inferenceStarted) {
                    inferenceStarted = true
                    startInference(
                        path = draft.audioPath,
                        lat = draft.latitude,
                        lon = draft.longitude,
                        recordedAt = draft.recordedAtUtcMs,
                    )
                }
            }
        }

        // Mirror player flows into UI state.
        scope.launch {
            player.position.collect { pos ->
                val pb = _state.value.playback
                if (pb is PlaybackState.Playing) {
                    _state.value = _state.value.copy(playback = PlaybackState.Playing(pos))
                }
            }
        }
        scope.launch {
            player.isPlaying.collect { playing ->
                val pb = _state.value.playback
                _state.value = _state.value.copy(
                    playback = when {
                        playing -> PlaybackState.Playing(player.position.value)
                        pb is PlaybackState.Playing -> PlaybackState.Paused(player.position.value)
                        else -> pb
                    },
                )
            }
        }
        scope.launch {
            player.lastError.collect { err ->
                if (err != null) {
                    _state.value = _state.value.copy(playback = PlaybackState.Error(err))
                }
            }
        }
    }

    private fun startInference(path: String, lat: Double?, lon: Double?, recordedAt: Long) {
        inferenceJob = scope.launch {
            _state.value = _state.value.copy(inferenceProgress = 0f, inferenceError = null)
            val outcome = inference.run(path, lat, lon, recordedAt) { p ->
                _state.value = _state.value.copy(inferenceProgress = p)
            }
            when (outcome) {
                is InferenceOutcome.Success -> {
                    // Merge instead of replace so user-triggered re-runs add to
                    // existing detections (e.g. BirdNET on a draft that already
                    // has Perch rows produces a single union with both source
                    // badges). For the initial PENDING_INFERENCE path the
                    // existing list is empty, so merge == replace there.
                    // promoteToReviewed=true so Submit becomes enabled by the
                    // user's checkbox selection alone — auto-promotion runs
                    // in the same IO block as attachDetections so collectors
                    // never see an intermediate PENDING_REVIEW snapshot.
                    mergeAndPersist(
                        newModelId = outcome.modelId,
                        newModelVersion = outcome.modelVersion,
                        freshDetections = outcome.detections,
                        promoteToReviewed = true,
                    )
                    _windowPreds.value = outcome.windows
                    _state.value = _state.value.copy(inferenceProgress = null)
                    // Ensure annotation runs on the final merged species list.
                    // The Room INSERT emission may have triggered it already; if
                    // not (same species set hit cache), start it explicitly here.
                    launchAnnotationIfIdle()
                }
                is InferenceOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        inferenceProgress = null,
                        inferenceError = outcome.message,
                    )
                }
            }
        }
    }

    fun toggle(detectionId: Long, selected: Boolean) {
        scope.launch(ioDispatcher) { repo.setSelection(detectionId, selected) }
    }

    fun loadObservationDetail(detectionId: Long, observationUrl: String) {
        val idOrUuid = observationUrl.trimEnd('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: return

        if (observationDetailCache.containsKey(idOrUuid)) {
            val cached = observationDetailCache[idOrUuid]
            _state.update { s ->
                s.copy(species = s.species.map { row ->
                    if (row.detectionId == detectionId) row.copy(
                        observationDetailState = if (cached != null)
                            ObservationDetailLoadState.Loaded(cached)
                        else
                            ObservationDetailLoadState.Error("Observation not available"),
                    ) else row
                })
            }
            return
        }

        _state.update { s ->
            s.copy(species = s.species.map { row ->
                if (row.detectionId == detectionId) row.copy(
                    observationDetailState = ObservationDetailLoadState.Loading,
                ) else row
            })
        }

        scope.launch {
            val detail = runCatching { observationFetcher(idOrUuid) }.getOrNull()
            observationDetailCache[idOrUuid] = detail
            _state.update { s ->
                s.copy(species = s.species.map { row ->
                    if (row.detectionId == detectionId &&
                        row.observationDetailState is ObservationDetailLoadState.Loading) {
                        row.copy(
                            observationDetailState = if (detail != null)
                                ObservationDetailLoadState.Loaded(detail)
                            else
                                ObservationDetailLoadState.Error("Could not load observation details"),
                        )
                    } else row
                })
            }
        }
    }

    fun collapseObservationDetail(detectionId: Long) {
        _state.update { s ->
            s.copy(species = s.species.map { row ->
                if (row.detectionId == detectionId) row.copy(
                    observationDetailState = ObservationDetailLoadState.NotLoaded,
                ) else row
            })
        }
    }

    /**
     * Re-runs the BirdNET inference path and merges the resulting detections
     * with what is already on the draft (Perch rows survive; matching taxa
     * pick up an extra `birdnet_v2_4` source badge with its own confidence).
     * No-op while another inference job is in flight.
     */
    fun reanalyzeBirdnet() {
        if (_state.value.inferenceProgress != null) return
        if (_state.value.perchProgress != null) return
        val path = _state.value.audioPath ?: return
        val lat = _state.value.latitude
        val lon = _state.value.longitude
        val recordedAt = _state.value.recordedAtUtcMs
        inferenceJob?.cancel()
        // Block init's flow collector from kicking off inference if Room
        // re-emits during the run — this is the single entry point.
        inferenceStarted = true
        _windowPreds.value = emptyList()
        _state.value = _state.value.copy(
            inferenceError = null,
            inferenceProgress = 0f,
        )
        startInference(path = path, lat = lat, lon = lon, recordedAt = recordedAt)
    }

    /**
     * Runs Perch v2 on the current saved WAV and merges the resulting
     * detections with the draft's existing per-species rows. No-op when:
     *  - Perch isn't installed (button shouldn't be visible),
     *  - Another Perch run is already in flight,
     *  - The draft has no audio path or live inference is currently running.
     *
     * Merge policy: union by `taxonScientificName`, taking max(maxConfidence),
     * sum(detectedWindows), min(firstSeenMs), max(lastSeenMs), and per-source
     * max for [AggregatedDetection.confidenceBySource]. Result is sorted by
     * maxConfidence desc, then persisted via [DraftRepository.attachDetections]
     * which replaces the row set atomically — no historical detections are
     * lost because we pass the merged union.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    fun analyzeWithPerch() {
        if (!_state.value.isPerchInstalled) return
        if (_state.value.perchProgress != null) return
        if (_state.value.inferenceProgress != null) return
        val path = _state.value.audioPath ?: return
        val lat = _state.value.latitude
        val lon = _state.value.longitude
        val recordedAt = _state.value.recordedAtUtcMs
        _state.value = _state.value.copy(perchProgress = 0f, perchError = null)
        perchJob = scope.launch {
            try {
                val outcome = perchAnalysis.run(path, lat, lon, recordedAt) { p ->
                    _state.value = _state.value.copy(perchProgress = p.coerceIn(0f, 1f))
                }
                when (outcome) {
                    is PerchAnalysisOutcome.Success -> {
                        mergeAndPersist(
                            newModelId = "perch_v2",
                            newModelVersion = "perch",
                            freshDetections = outcome.detections,
                        )
                        launchAnnotationIfIdle()
                    }
                    is PerchAnalysisOutcome.Failure -> {
                        _state.value = _state.value.copy(perchError = outcome.message)
                    }
                    PerchAnalysisOutcome.NotInstalled -> {
                        _state.value = _state.value.copy(
                            perchError = "Perch model is not installed",
                        )
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ReviewViewModel", "analyzeWithPerch failed", t)
                _state.value = _state.value.copy(
                    perchError = t.message ?: t::class.simpleName.orEmpty(),
                )
            } finally {
                _state.value = _state.value.copy(perchProgress = null)
                perchInstalled = runCatching { perchInstalledProbe() }.getOrDefault(perchInstalled)
                recomputePerchEligibility()
            }
        }
    }

    /**
     * Folds [freshDetections] into the draft's current detections (read fresh
     * from [DraftRepository.observeWithDetections]) and writes the union back
     * via [DraftRepository.attachDetections]. [newModelId]/[newModelVersion]
     * are appended to the draft's stored model lineage so the persisted
     * `modelId` reads as `birdnet_v2_4,perch_v2` after both have run.
     *
     * When [promoteToReviewed] is true and the merged set is non-empty the
     * draft is also marked REVIEWED inside the same IO-dispatched block so
     * the [DraftRepository.observeWithDetections] flow does not emit an
     * intermediate `PENDING_REVIEW` snapshot between the two writes.
     */
    private suspend fun mergeAndPersist(
        newModelId: String,
        newModelVersion: String,
        freshDetections: List<AggregatedDetection>,
        promoteToReviewed: Boolean = false,
    ) = persistMutex.withLock {
        val dwd = repo.observeWithDetections(draftId).first()
        val existing = dwd.detections.map { e ->
            AggregatedDetection(
                taxonScientificName = e.taxonScientificName,
                taxonCommonName = e.taxonCommonName,
                maxConfidence = e.maxConfidence,
                detectedWindows = e.detectedWindows,
                firstSeenMs = e.firstSeenMs,
                lastSeenMs = e.lastSeenMs,
                confidenceBySource = SourceConfidences.decode(e.sources),
                fragmentRanges = FragmentRanges.decode(e.fragmentRanges),
                aggregatedConfidence = e.aggregatedConfidence,
            )
        }
        val merged = mergeBySpecies(existing, freshDetections)
        val priorModelId = dwd.draft.modelId
        val combinedModelId = when {
            priorModelId.isNullOrBlank() -> newModelId
            priorModelId.split(',', '+').contains(newModelId) -> priorModelId
            else -> "$priorModelId,$newModelId"
        }
        val priorVersion = dwd.draft.modelVersion ?: ""
        val combinedVersion = when {
            priorVersion.isBlank() -> newModelVersion
            newModelVersion.isBlank() -> priorVersion
            priorVersion.contains(newModelVersion) -> priorVersion
            else -> "$priorVersion+$newModelVersion"
        }
        withContext(ioDispatcher) {
            repo.attachDetections(
                draftId = draftId,
                modelId = combinedModelId,
                modelVersion = combinedVersion.trim('+'),
                items = merged,
            )
            if (promoteToReviewed && merged.isNotEmpty()) {
                repo.markReviewed(draftId)
            }
        }
    }

    /**
     * Recomputes [ReviewUiState.isPerchInstalled] purely from the cached
     * [perchInstalled] flag. The "already analyzed with Perch" gate was
     * dropped — both Re-analyze paths merge into existing detections, so a
     * repeat Perch run is harmless and may produce additional detections
     * with newer thresholds.
     */
    private fun recomputePerchEligibility() {
        if (_state.value.isPerchInstalled != perchInstalled) {
            _state.value = _state.value.copy(isPerchInstalled = perchInstalled)
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        scope.launch {
            withContext(ioDispatcher) { repo.markReviewed(draftId) }
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit = {}) {
        scope.launch {
            withContext(ioDispatcher) { repo.delete(draftId) }
            onDeleted()
        }
    }

    /**
     * Pushes the current draft to iNaturalist. Reflects progress on
     * [ReviewUiState.inatSubmission] so the screen can show a spinner and
     * the resulting observation URL or error.
     *
     * Logs the caller's stack trace so we can debug stray re-invocations —
     * users have reported it firing without an explicit tap, which would
     * otherwise be invisible (the lambda is just `vm::submitToINaturalist`).
     */
    fun submitToINaturalist() {
        if (_state.value.inatSubmission == InatSubmissionState.InProgress) return
        // Already finished in this VM instance — block re-fire so a stray
        // recomposition or second click doesn't duplicate the upload.
        if (_state.value.inatSubmission is InatSubmissionState.Done) return
        // If a prior session already marked the draft UPLOADED, the user must
        // explicitly reset before re-submitting (no safe-by-default re-fire).
        if (_state.value.status == DraftStatus.UPLOADED) {
            android.util.Log.w(
                "ReviewViewModel",
                "submitToINaturalist ignored — draft is already UPLOADED",
                Throwable("call site"),
            )
            return
        }
        android.util.Log.i(
            "ReviewViewModel",
            "submitToINaturalist invoked",
            Throwable("call site"),
        )
        _state.value = _state.value.copy(inatSubmission = InatSubmissionState.InProgress)
        scope.launch {
            val token = tokenProvider()
            if (token.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    inatSubmission = InatSubmissionState.Failed(
                        "iNaturalist session expired — open Settings and tap Log in again",
                    ),
                )
                return@launch
            }
            val outcome = submission.submit(token, draftId)
            _state.value = _state.value.copy(
                inatSubmission = when (outcome) {
                    is InatSubmissionOutcome.Success -> InatSubmissionState.Done(outcome.urls)
                    is InatSubmissionOutcome.Failure -> InatSubmissionState.Failed(outcome.message)
                },
            )
        }
    }

    fun resetInatSubmission() {
        _state.value = _state.value.copy(inatSubmission = InatSubmissionState.Idle)
    }

    /**
     * Lazily builds (or loads cached) waveform peaks and spectrogram PNG for
     * the current draft. Safe to call repeatedly — only the first call kicks
     * off the work; subsequent calls are no-ops while the work is in flight
     * or after it completes successfully.
     *
     * The screen calls this from a `LaunchedEffect(state.audioPath)` once the
     * audio path is known. Failures are silent (the on-screen visuals simply
     * do not appear) — a missing PNG is not a UX-blocking error.
     */
    @Suppress("TooGenericExceptionCaught")
    fun ensureVisuals(filesDir: File) {
        if (visualsStarted) return
        val path = _state.value.audioPath ?: return
        visualsStarted = true
        visualsJob = scope.launch {
            try {
                val v = withContext(ioDispatcher) { visuals.build(path, draftId, filesDir) }
                _spectrogramFile.value = v.spectrogramFile
                _waveformPeaks.value = v.waveformPeaks
            } catch (t: Throwable) {
                // Reset so a later retry (e.g. process restart, screen re-entry)
                // can run. State stays null, screen renders without visuals.
                visualsStarted = false
                android.util.Log.w("ReviewViewModel", "ensureVisuals failed for draft $draftId", t)
            }
        }
    }

    fun play() {
        val path = effectivePlaybackPath() ?: return
        player.start(path)
    }
    fun pause() { player.pause() }
    fun seekTo(ms: Long) { player.seekTo(ms) }

    /**
     * Selects the audio source that the player should use right now: the
     * denoise-preview WAV when the toggle is on AND the artifact has been
     * built, otherwise the original recording.
     */
    private fun effectivePlaybackPath(): String? = if (
        _state.value.denoisePreviewEnabled && _denoisedAudioFile.value != null
    ) {
        _denoisedAudioFile.value?.absolutePath
    } else {
        _state.value.audioPath
    }

    /**
     * Toggles the Review screen's noise-reduction preview. Pauses any current
     * playback so the next Play uses the new source. On first activation, the
     * denoise pipeline runs in the background and writes a WAV + PNG under
     * [filesDir]; subsequent toggles reuse the cached files.
     */
    fun setDenoisePreviewEnabled(enabled: Boolean, filesDir: File) {
        if (_state.value.denoisePreviewEnabled == enabled) return
        player.pause()
        _state.value = _state.value.copy(denoisePreviewEnabled = enabled)
        if (enabled && _denoisedAudioFile.value == null && !_state.value.denoisingInProgress) {
            buildDenoiseArtifacts(filesDir)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildDenoiseArtifacts(filesDir: File) {
        val src = _state.value.audioPath ?: return
        _state.value = _state.value.copy(denoisingInProgress = true)
        scope.launch {
            try {
                val artifacts = withContext(ioDispatcher) {
                    DenoisedArtifactsBuilder.build(File(src), draftId, filesDir)
                }
                _denoisedAudioFile.value = artifacts.audioFile
                _denoisedSpectrogramFile.value = artifacts.spectrogramFile
            } catch (t: Throwable) {
                android.util.Log.w("ReviewViewModel", "Denoise preview build failed", t)
                _state.value = _state.value.copy(denoisePreviewEnabled = false)
            } finally {
                _state.value = _state.value.copy(denoisingInProgress = false)
            }
        }
    }

    /**
     * Sets [_highlight] to [id] and auto-clears it after [HighlightDurationMs] ms.
     * Re-tapping while a previous flash is in flight cancels the prior timer so
     * the new id stays visible for the full duration. Passing `null` clears
     * immediately.
     */
    fun highlight(id: Long?) {
        highlightJob?.cancel()
        _highlight.value = id
        if (id == null) return
        highlightJob = scope.launch {
            kotlinx.coroutines.delay(HighlightDurationMs)
            if (_highlight.value == id) _highlight.value = null
        }
    }

    /**
     * Maps a tapped [WindowPrediction] to its species row (by scientific name),
     * seeks the player to the window start, and flashes the row's overlays.
     * No-ops if the prediction does not correspond to a persisted detection
     * (which can happen briefly between inference completion and Room write).
     */
    fun onWindowTapped(prediction: WindowPrediction) {
        val row = _state.value.species.firstOrNull {
            it.taxonScientificName == prediction.taxonScientificName
        } ?: return
        player.seekTo(prediction.startMs)
        highlight(row.detectionId)
    }

    /**
     * Release the underlying [AudioPlayer] and cancel all VM-owned coroutines.
     * Must be called by whichever scope owns the VM — Hilt's
     * [ViewModel.onCleared] does NOT cascade to this delegate (the factory
     * pattern in [ReviewViewModelFactory] creates instances outside any
     * [androidx.lifecycle.ViewModelStore], so [viewModelScope] is never
     * cancelled automatically).
     *
     * When an [externalScope] was injected (tests), only the player is
     * released — the test owns scope cancellation via `runTest`.
     */
    fun release() {
        player.release()
        if (ownsScope) {
            viewModelScope.coroutineContext[Job]?.cancel()
        }
    }

    /**
     * Triggers annotation on current species after inference/merge completes,
     * but only if no annotation is already in flight (avoids double-cancelling
     * a job that was already started by the Room INSERT emission).
     */
    private fun launchAnnotationIfIdle() {
        if (annotationJob?.isActive == true) return
        val s = _state.value
        val lat = s.latitude ?: return
        val lon = s.longitude ?: return
        if (s.species.isEmpty()) return
        Log.d("ReviewVM", "launchAnnotationIfIdle: no active job, launching on ${s.species.size} species")
        launchAnnotation(s.species, lat, lon)
    }

    /**
     * Annotates [rows] with regional presence data from iNaturalist. No-op
     * when [regionFilter] is null. Any previously running annotation job is
     * cancelled so only the latest species list triggers network calls.
     *
     * Results are stored in [regionalStatusCache] and merged into
     * [_state].species so the UI updates without waiting for a Room re-emission.
     */
    private fun launchAnnotation(rows: List<SpeciesRow>, lat: Double, lon: Double) {
        val filter = regionFilter ?: run {
            Log.d("ReviewVM", "launchAnnotation: regionFilter is null, skipping")
            return
        }
        val wasRunning = annotationJob?.isActive == true
        annotationJob?.cancel()
        Log.d("ReviewVM", "launchAnnotation: starting for ${rows.size} species (cancelled prev=$wasRunning): ${rows.map { it.taxonScientificName }}")
        annotationJob = scope.launch {
            val radiusKm = regionRadiusKmProvider()
            try {
                val annotated = filter.annotate(
                    rows.map { r ->
                        AggregatedDetection(
                            taxonScientificName = r.taxonScientificName,
                            taxonCommonName = r.taxonCommonName,
                            maxConfidence = r.maxConfidence,
                            detectedWindows = r.detectedWindows,
                            firstSeenMs = r.firstSeenMs,
                            lastSeenMs = r.lastSeenMs,
                        )
                    },
                    lat, lon, radiusKm,
                )
                annotated.forEach { det ->
                    regionalStatusCache[det.taxonScientificName] = det.regionalStatus
                }
                Log.d("ReviewVM", "launchAnnotation: complete, updating state with ${annotated.size} statuses")
                _state.update { s ->
                    s.copy(
                        species = s.species.map { row ->
                            row.copy(regionalStatus = regionalStatusCache[row.taxonScientificName])
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("ReviewVM", "launchAnnotation: job cancelled after annotating ${regionalStatusCache.size} species")
                throw e
            }
        }
    }

    private companion object {
        /** Milliseconds an overlay/row stays highlighted after a tap. */
        const val HighlightDurationMs = 800L
    }
}

/**
 * Hilt-injectable factory that builds [ReviewViewModel] instances on demand.
 * Lets us instantiate one VM per [HorizontalPager] page without fighting
 * Hilt's "one [ViewModel] per [ViewModelStore] key" constraint —
 * [ReviewPagerViewModel] is the single Hilt VM, and it asks this factory
 * for a fresh delegate per draft id as the user swipes.
 */
@Singleton
@Suppress("LongParameterList")
class ReviewViewModelFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DraftRepository,
    private val models: List<@JvmSuppressWildcards BioacousticModel>,
    private val descriptors: List<@JvmSuppressWildcards ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val submitter: INatSubmitter,
    private val inatObservationsDao: InatObservationDao,
    private val inatClient: INaturalistClient,
    private val regionFilter: RegionFilter,
    private val yamNetGate: YamNetGate?,
    private val birdNetMeta: BirdNetMetaModel?,
    private val inatAuth: com.sound2inat.inat.INatAuthRepository,
) {
    /** Cache root used by the screen's `ensureVisuals` call. */
    val filesDir: File get() = context.filesDir

    fun create(draftId: String): ReviewViewModel = ReviewViewModel(
        draftId = draftId,
        repo = repo,
        player = MediaPlayerAudioPlayer(),
        inference = ProductionInferenceJob(
            models,
            descriptors,
            modelManager,
            settings,
            regionFilter,
            yamNetGate,
            birdNetMeta,
        ),
        visuals = ProductionVisualsProvider(),
        submission = InatSubmissionJob { token, id ->
            // Pulling the freshest draft + detections so the submitter sees the
            // user's current selection, not a stale snapshot.
            val dwd = repo.observeWithDetections(id).first()
            when (val r = submitter.submit(token, dwd)) {
                is INatSubmitter.Result.Ok -> InatSubmissionOutcome.Success(r.urls)
                is INatSubmitter.Result.Failure -> InatSubmissionOutcome.Failure(r.message)
            }
        },
        tokenProvider = { inatAuth.getValidToken() },
        inatObservationsFlow = inatObservationsDao.observeForDraft(draftId)
            .map { rows -> rows.map { it.taxonScientificName to it.observationUrl } },
        photoFetcher = { name -> inatClient.fetchTaxonPhotoUrl(name) },
        perchAnalysis = ProductionPerchAnalysisJob(models, modelManager, settings, yamNetGate),
        perchInstalledProbe = {
            modelManager.stateFor(com.sound2inat.modelmanager.PerchV2.descriptor) is
                ModelInstallState.Ready
        },
        regionFilter = regionFilter,
        minWindowsProvider = { settings.minWindows.first() },
        regionRadiusKmProvider = { settings.regionRadiusKm.first() },
        observationFetcher = { id -> inatClient.getObservation(id) },
    )
}

/**
 * Hilt VM owning the ordered list of draft ids and the navigation entry
 * point's initial draft id. The [HorizontalPager] in [ReviewScreen] reads
 * [orderedDraftIds] to know how many pages to render and uses [factory]
 * to build a fresh [ReviewViewModel] per page.
 */
@HiltViewModel
class ReviewPagerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val factory: ReviewViewModelFactory,
    repo: DraftRepository,
) : ViewModel() {
    val initialDraftId: String = checkNotNull(savedStateHandle.get<String>("draftId")) {
        "Review screen requires draftId nav arg"
    }

    /**
     * All drafts in the same order as the Home list (newest first). Swipe
     * left on page N moves to N+1 (older recording); swipe right moves to
     * N-1 (newer). Empty list means "Home is empty" — the screen should
     * pop back rather than render an empty Pager.
     */
    val orderedDraftIds: StateFlow<List<String>> = repo.observeAll()
        .map { drafts -> drafts.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/**
 * Unions two aggregated-detection lists by `taxonScientificName`. For species
 * present in both: max(maxConfidence), sum(detectedWindows), min(firstSeenMs),
 * max(lastSeenMs), per-source max for [AggregatedDetection.confidenceBySource];
 * common name preferred from the side that has one. Result is sorted by
 * maxConfidence descending so the UI ordering matches a fresh aggregator pass.
 */
internal fun mergeBySpecies(
    existing: List<AggregatedDetection>,
    incoming: List<AggregatedDetection>,
): List<AggregatedDetection> {
    val byName = LinkedHashMap<String, AggregatedDetection>()
    for (d in existing) byName[d.taxonScientificName] = d
    for (d in incoming) {
        val prior = byName[d.taxonScientificName]
        if (prior == null) {
            byName[d.taxonScientificName] = d
            continue
        }
        val mergedSources = (prior.confidenceBySource.keys + d.confidenceBySource.keys)
            .associateWith { key ->
                maxOf(
                    prior.confidenceBySource[key] ?: 0f,
                    d.confidenceBySource[key] ?: 0f,
                )
            }
        byName[d.taxonScientificName] = AggregatedDetection(
            taxonScientificName = d.taxonScientificName,
            taxonCommonName = prior.taxonCommonName ?: d.taxonCommonName,
            maxConfidence = maxOf(prior.maxConfidence, d.maxConfidence),
            detectedWindows = prior.detectedWindows + d.detectedWindows,
            firstSeenMs = minOf(prior.firstSeenMs, d.firstSeenMs),
            lastSeenMs = maxOf(prior.lastSeenMs, d.lastSeenMs),
            confidenceBySource = mergedSources,
        )
    }
    return byName.values.sortedByDescending { it.maxConfidence }
}

/** Default sink used in tests; never builds anything. */
internal object NoopVisualsProvider : VisualsProvider {
    override suspend fun build(audioPath: String, draftId: String, filesDir: File): Visuals =
        error("NoopVisualsProvider should not be invoked; supply a real VisualsProvider")
}

/**
 * Production [VisualsProvider]. Reads the WAV via [WavReader.readMono16],
 * normalises to `[-1, 1]`, runs [SpectrogramRenderer] + [SpectrogramBitmap]
 * to cache a PNG under `<filesDir>/spectrograms/<draftId>.png`, and computes
 * waveform peaks via [WaveformBitmap.peaks].
 *
 * Cache policy: if the PNG already exists, the spectrogram step is skipped
 * (we only re-read the WAV for waveform peaks, which are not cached). This
 * keeps the second open instant for the spectrogram while the cheap peak
 * computation runs again on each open.
 */
internal class ProductionVisualsProvider(
    private val renderer: SpectrogramRenderer = SpectrogramRenderer(),
) : VisualsProvider {

    override suspend fun build(audioPath: String, draftId: String, filesDir: File): Visuals {
        val (shorts, _) = WavReader.readMono16(File(audioPath))
        val floats = FloatArray(shorts.size) { i -> shorts[i] / Short.MAX_VALUE.toFloat() }
        val pngDir = File(filesDir, "spectrograms").apply { mkdirs() }
        val pngFile = File(pngDir, "$draftId.png")
        if (!pngFile.exists()) {
            val pixels = renderer.render(floats)
            if (pixels.isNotEmpty()) {
                SpectrogramBitmap.writePng(pixels, pngFile)
            }
        }
        val peaks = WaveformBitmap.peaks(floats)
        return Visuals(spectrogramFile = pngFile, waveformPeaks = peaks)
    }
}

/**
 * Wires the production stack: pairs each [BioacousticModel] with its
 * [ModelDescriptor] by `modelId`, runs every installed (Ready) model
 * sequentially over the same WAV, and concatenates per-window predictions
 * before [DetectionAggregator] groups them per taxon.
 *
 * Sequential — not parallel — because TFLite interpreters are not
 * thread-safe for concurrent inference and Perch v2 alone already pegs
 * memory on a phone. Progress is reported as a smoothed fraction across
 * the full multi-model run.
 *
 * If no model is installed, returns [InferenceOutcome.Failure]. If only
 * one is installed, the run is identical to the legacy single-model path,
 * with `confidenceBySource` reflecting just that one source.
 */
private class ProductionInferenceJob(
    private val models: List<BioacousticModel>,
    private val descriptors: List<ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val regionFilter: RegionFilter,
    private val yamNetGate: YamNetGate?,
    private val birdNetMeta: BirdNetMetaModel?,
) : InferenceJob {

    @Suppress("TooGenericExceptionCaught", "LongMethod", "NestedBlockDepth")
    override suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): InferenceOutcome = withContext(Dispatchers.IO) {
        val descriptorsById = descriptors.associateBy { it.id }
        val ready = models.mapNotNull { m ->
            val d = descriptorsById[m.modelId] ?: return@mapNotNull null
            val state = modelManager.stateFor(d) as? ModelInstallState.Ready
                ?: return@mapNotNull null
            Triple(m, d, state)
        }
        if (ready.isEmpty()) {
            android.util.Log.w(TAG, "No model installed; skipping inference")
            return@withContext InferenceOutcome.Failure("No model installed")
        }
        val minConf = settings.minConfidenceDisplay.first()
        val minWin = settings.minWindows.first()
        val spectralEnabled = settings.spectralSubtractionEnabled.first()
        val yamNetEnabled = settings.yamNetGateEnabled.first()
        val activeGate = if (yamNetEnabled) yamNetGate else null
        val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = minWin)
        // Compute BirdNET location/time priors once per run; null when the meta
        // model isn't installed, the user disabled it in Settings, or coords
        // aren't known. Applied later only to BirdNET window predictions —
        // Perch has its own label space.
        val birdNetMetaEnabled = settings.birdNetMetaEnabled.first()
        val birdNetPriors = if (birdNetMetaEnabled) {
            computeBirdNetPriors(latitude, longitude, observedAtMillis)
        } else {
            null
        }
        val allPreds = ArrayList<WindowPrediction>()
        val succeeded = ArrayList<BioacousticModel>()
        val perModelErrors = ArrayList<String>()
        val total = ready.size
        // Per-model try/catch so a broken model (e.g. wrong output shape)
        // doesn't kill the entire run — the other models still produce
        // detections. The user sees a partial-success banner with the
        // failing model's error message.
        for ((idx, triple) in ready.withIndex()) {
            val (model, _, state) = triple
            try {
                android.util.Log.i(
                    TAG,
                    "Running ${model.modelId} v${model.modelVersion} " +
                        "(${idx + 1}/$total) on $audioPath",
                )
                model.load(state.modelFile, state.labelsFile)
                // Fresh SpectralSubtractor per model — its EMA noise profile is sized to
                // the FFT of this model's window length and must not leak between models.
                val subtractor = if (spectralEnabled) SpectralSubtractor() else null
                val runner = InferenceRunner(
                    model,
                    spectralSubtractor = subtractor,
                    yamNetGate = activeGate,
                )
                val perModel = coroutineScope {
                    val collector = launch {
                        runner.progress.collect { p ->
                            onProgress((idx + p) / total)
                        }
                    }
                    val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                    collector.cancel()
                    result
                }
                val rescaled = if (model.modelId == BIRDNET_MODEL_ID && birdNetPriors != null) {
                    applyBirdNetPriors(perModel, birdNetPriors)
                } else {
                    perModel
                }
                allPreds += rescaled
                succeeded += model
                android.util.Log.i(
                    TAG,
                    "${model.modelId} produced ${perModel.size} window predictions" +
                        if (rescaled.size != perModel.size) {
                            " (${perModel.size - rescaled.size} suppressed by regional priors)"
                        } else {
                            ""
                        },
                )
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Inference failed for ${model.modelId}", t)
                perModelErrors += "${model.modelId}: ${t.message ?: t::class.simpleName.orEmpty()}"
            } finally {
                runCatching { model.close() }
                    .onFailure { android.util.Log.w(TAG, "model.close() threw", it) }
            }
        }
        onProgress(1f)
        if (succeeded.isEmpty()) {
            android.util.Log.e(TAG, "All ${ready.size} model(s) failed: $perModelErrors")
            return@withContext InferenceOutcome.Failure(
                perModelErrors.joinToString(separator = "; ").ifEmpty { "Inference failed" },
            )
        }
        val ids = succeeded.joinToString(separator = "+") { it.modelId }
        val versions = succeeded.joinToString(separator = "+") { it.modelVersion }
        val rawDetections = aggregator.aggregate(allPreds)
        // Below-threshold "candidates" used to be surfaced separately as a
        // grayed-out list, but the 1% absolute floor produced too much noise
        // (BirdNET v2.4 GLOBAL spreads tiny scores across hundreds of similar
        // species). Honoring the user-set threshold strictly is cleaner.

        if (latitude != null && longitude != null) {
            settings.setLastKnownCoords(latitude, longitude)
        }

        InferenceOutcome.Success(
            modelId = ids,
            modelVersion = versions,
            detections = rawDetections,
            windows = allPreds,
        )
    }

    /**
     * Runs the BirdNET location/time meta-model once for this recording and
     * returns the per-species multiplier map, or null when we have no priors
     * to apply (model not installed, coords unknown, or any internal failure).
     * Coords fall back to [Settings.lastKnownLat]/[lastKnownLon] when the
     * recording itself has no GPS fix attached.
     */
    private suspend fun computeBirdNetPriors(
        latitude: Double?,
        longitude: Double?,
        recordedAtMillis: Long,
    ): Map<String, Float>? {
        val meta = birdNetMeta ?: return null
        val effectiveLat = latitude ?: settings.lastKnownLat.first() ?: return null
        val effectiveLon = longitude ?: settings.lastKnownLon.first() ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (recordedAtMillis > 0L) recordedAtMillis else System.currentTimeMillis()
        val week = BirdNetMetaModel.weekOfYearFromDayOfYear(cal.get(Calendar.DAY_OF_YEAR))
        return meta.priorsByScientificName(effectiveLat, effectiveLon, week)
    }

    /**
     * Folds [priors] into [preds]: species absent from the map (multiplier 0)
     * are dropped entirely so they never reach the aggregator; species with a
     * non-unit multiplier have their confidence scaled accordingly. Pass-through
     * for species with multiplier 1.0 to avoid allocating new objects.
     */
    private fun applyBirdNetPriors(
        preds: List<WindowPrediction>,
        priors: Map<String, Float>,
    ): List<WindowPrediction> = preds.mapNotNull { wp ->
        val mult = priors[wp.taxonScientificName] ?: 0f
        when {
            mult <= 0f -> null
            mult >= 1f -> wp
            else -> wp.copy(confidence = wp.confidence * mult)
        }
    }

    private companion object {
        const val TAG = "ProductionInferenceJob"
        const val BIRDNET_MODEL_ID = "birdnet_v2_4"
    }
}

/**
 * Production [PerchAnalysisJob]. Resolves the Perch [BioacousticModel] from the
 * injected list, ensures the artifact is installed, and runs
 * [InferenceRunner] over the WAV. The resulting per-window predictions are
 * aggregated with the user's `minConfidenceDisplay` threshold but NOT subjected
 * to the regional filter — Perch covers taxa (frogs, insects, mammals) for
 * which the regional service has no priors yet.
 */
private class ProductionPerchAnalysisJob(
    private val models: List<BioacousticModel>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val yamNetGate: YamNetGate?,
) : PerchAnalysisJob {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): PerchAnalysisOutcome = withContext(Dispatchers.IO) {
        val perch = models.firstOrNull { it.modelId == "perch_v2" }
            ?: return@withContext PerchAnalysisOutcome.NotInstalled
        val state = modelManager.stateFor(com.sound2inat.modelmanager.PerchV2.descriptor)
            as? ModelInstallState.Ready
            ?: return@withContext PerchAnalysisOutcome.NotInstalled
        try {
            perch.load(state.modelFile, state.labelsFile)
            val subtractor = if (settings.spectralSubtractionEnabled.first()) {
                SpectralSubtractor()
            } else {
                null
            }
            val gate = if (settings.yamNetGateEnabled.first()) yamNetGate else null
            val runner = InferenceRunner(
                model = perch,
                spectralSubtractor = subtractor,
                yamNetGate = gate,
            )
            val preds = coroutineScope {
                val collector = launch {
                    runner.progress.collect { p -> onProgress(p) }
                }
                val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                collector.cancel()
                result
            }
            onProgress(1f)
            val minConf = settings.minConfidenceDisplay.first()
            val minWin = settings.minWindows.first()
            val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = minWin)
            PerchAnalysisOutcome.Success(aggregator.aggregate(preds))
        } catch (t: Throwable) {
            android.util.Log.e("ProductionPerchAnalysisJob", "Perch run failed", t)
            PerchAnalysisOutcome.Failure(t.message ?: t::class.simpleName.orEmpty())
        } finally {
            runCatching { perch.close() }
        }
    }
}
