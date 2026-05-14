package com.sound2inat.app.ui.review

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.AudioExportManager
import com.sound2inat.app.data.Settings
import com.sound2inat.app.inference.InferenceQueue
import com.sound2inat.app.inference.JobStatus
import com.sound2inat.app.inference.QueuedJob
import com.sound2inat.app.ui.FILE_PROVIDER_AUTHORITY
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inat.INatSubmitter
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.ObservationDetail
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inat.RegionalStatusRepository
import com.sound2inat.inat.WavTrimmer
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.FragmentRanges
import com.sound2inat.inference.InferenceJob
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.PerchAnalysisJob
import com.sound2inat.inference.PerchAnalysisOutcome
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.inference.SourceStats
import com.sound2inat.inference.WavReader
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.inference.denoiseFull
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.recorder.WavWriter
import com.sound2inat.storage.DraftPhotoDao
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.PhotoFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

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
    suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
        audioProcessingConfig: ReviewAudioProcessingConfig,
    ): Visuals
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
 * Submission seam abstracted from the production [INatSubmitter] so the VM
 * is unit-testable without going near OkHttp. Test fakes return canned
 * outcomes; the production wiring forwards to [INatSubmitter.submit].
 */
fun interface InatSubmissionJob {
    suspend fun submit(
        token: String,
        draftId: String,
        habitatPhotos: List<File>,
        includeHabitatPhotoByTaxon: Map<String, Boolean>,
        spectrogramPhoto: File?,
        sourceAudioOverride: File?,
    ): InatSubmissionOutcome
}

sealed interface InatSubmissionOutcome {
    data class Success(val urls: List<String>) : InatSubmissionOutcome
    data class Failure(val message: String) : InatSubmissionOutcome
}

/**
 * Abstraction over saving audio to public storage. Lets VM unit tests
 * inject a fake save operation without touching Android MediaStore.
 * Returns the [Uri] of the saved file, or null if not applicable.
 * The ViewModel discards the return value — it is exposed only for
 * callers that need to open or share the just-saved item.
 */
fun interface AudioSaver {
    suspend fun saveToDownloads(file: File, displayName: String): Uri?
}

fun interface ProcessedAudioProvider {
    suspend fun materialize(
        originalAudioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewAudioProcessingConfig,
    ): File
}

fun interface SpectrogramPngWriter {
    fun write(pixels: Array<IntArray>, file: File)
}

@Suppress("LongParameterList")
class ReviewViewModel(
    private val draftId: String,
    private val repo: DraftRepository,
    private val player: AudioPlayer,
    private val inference: InferenceJob,
    private val visuals: VisualsProvider = NoopVisualsProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val submission: InatSubmissionJob = InatSubmissionJob { _, _, _, _, _, _ ->
        InatSubmissionOutcome.Failure("No iNaturalist submitter configured")
    },
    private val processedAudio: ProcessedAudioProvider = ProcessedAudioProvider { path, _, _, _ ->
        File(path)
    },
    private val tokenProvider: suspend () -> String? = { null },
    private val inatObservationsFlow: kotlinx.coroutines.flow.Flow<List<InatObsEntry>> =
        kotlinx.coroutines.flow.flowOf(emptyList()),
    /** Returns the iNaturalist default photo medium_url for a scientific name, or null. */
    private val photoFetcher: suspend (String) -> String? = { null },
    private val perchAnalysis: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ ->
        PerchAnalysisOutcome.NotInstalled
    },
    private val inferenceReanalysis: InferenceJob = inference,
    private val perchReanalysis: PerchAnalysisJob = perchAnalysis,
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
    /**
     * Repository for regional-status results, shared across all [ReviewViewModel]
     * instances. Keyed by (taxonName, lat-lon-bucket) so results from different
     * recording locations do not collide. Null in unit tests that don't need it.
     */
    private val regionalStatusRepository: RegionalStatusRepository? = null,
    /**
     * Emits the list of habitat photos for this draft. Default is an empty flow
     * (no photos). Injected from [DraftPhotoDao.photosForDraft] in production
     * and from a [FakeDraftPhotoDao] in unit tests.
     */
    private val habitatPhotosFlow: kotlinx.coroutines.flow.Flow<List<DraftPhotoEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList()),
    /** DAO used to persist and delete habitat photos. Null in tests that don't need it. */
    private val photosDao: DraftPhotoDao? = null,
    /** File store used to create photo files for camera capture. Null in tests. */
    private val photoStore: PhotoFileStore? = null,
    private val audioSaver: AudioSaver = AudioSaver { _, _ ->
        throw UnsupportedOperationException("No AudioSaver configured")
    },
    // Default is used only in JVM tests; production factory passes context.cacheDir/export_clips.
    private val exportClipsDir: File = File(
        checkNotNull(System.getProperty("java.io.tmpdir")),
        "export_clips"
    ),
    private val queue: InferenceQueue,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    /** Statuses fetched during this VM's lifetime; used to pre-populate rows on DB re-emission. */
    private val annotatedStatuses: MutableMap<String, RegionalStatus?> = mutableMapOf()

    private val ownsScope: Boolean = externalScope == null
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(ReviewUiState(draftId = draftId))
    val state: StateFlow<ReviewUiState> = _state

    private val _processingProfile = MutableStateFlow(ReviewProcessingProfile.Default)
    val processingProfile: StateFlow<ReviewProcessingProfile> = _processingProfile

    private val _spectrogramFile = MutableStateFlow<File?>(null)
    val spectrogramFile: StateFlow<File?> = _spectrogramFile

    private val _spectrogramConfig = MutableStateFlow(ReviewProcessingProfile.Default.spectrogramConfig)
    val spectrogramConfig: StateFlow<ReviewSpectrogramConfig> = _spectrogramConfig

    private val _displayRange = MutableStateFlow(SpectrogramDisplayRange.BIRDNET_BIRD)
    val displayRange: StateFlow<SpectrogramDisplayRange> = _displayRange

    val visualsLoading: StateFlow<Boolean> = _state
        .map { it.visualsLoading }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val visualsError: StateFlow<String?> = _state
        .map { it.visualsError }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Cached so [setDisplayRange] can trigger a re-render without re-reading context. */
    private var cachedFilesDir: File? = null

    private val _waveformPeaks = MutableStateFlow<FloatArray?>(null)
    val waveformPeaks: StateFlow<FloatArray?> = _waveformPeaks

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

    private val observationDetailCache = mutableMapOf<String, ObservationDetail?>()
    private var annotationJob: Job? = null

    /** Latest playback position from the player; ticks every 50 ms during playback. */
    val playerPosition: StateFlow<Long> = player.position

    private var inferenceStarted = false
    private var visualsStarted = false
    private var visualsJob: Job? = null

    /** Last result of [perchInstalledProbe]; folded into [ReviewUiState.canAnalyzeWithPerch]. */
    private var perchInstalled: Boolean = false

    private var denoisedPath: String? = null
    private var denoiseJob: Job? = null

    init {
        scope.launch {
            // Probe once on init — the user can install Perch later, but the
            // typical flow is: install in Settings -> open a draft. Probing
            // again after each successful Perch reanalysis run keeps the flag current.
            perchInstalled = runCatching { perchInstalledProbe() }.getOrDefault(false)
            recomputePerchEligibility()
        }
        scope.launch {
            inatObservationsFlow.collect { rows ->
                _state.update { it.copy(inatObservations = rows) }
            }
        }
        scope.launch {
            habitatPhotosFlow.collect { photos ->
                _state.update { it.copy(habitatPhotos = photos) }
            }
        }
        scope.launch {
            queue.status
                .map { it[draftId] }
                .distinctUntilChanged()
                .collect { status ->
                    _state.update { s ->
                        s.copy(
                            inferenceProgress = (status as? JobStatus.Running)?.birdnetProgress,
                            perchProgress = (status as? JobStatus.Running)?.perchProgress,
                            queuePosition = (status as? JobStatus.Queued)?.position,
                            estimatedWaitMs = (status as? JobStatus.Queued)?.estimatedWaitMs,
                            inferenceError = if (status is JobStatus.Failed) {
                                status.message
                            } else {
                                s.inferenceError
                            },
                        )
                    }
                    if (status is JobStatus.Failed) queue.clearError(draftId)
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
                            confidenceBySource = SourceStats.decodeConfidenceOnly(e.sources),
                            taxonPhotoUrl = photoUrlCache[e.taxonScientificName],
                            regionalStatus = annotatedStatuses[e.taxonScientificName],
                            observationDetailState = prevDetailStates[e.id] ?: ObservationDetailLoadState.NotLoaded,
                            fragmentRanges = FragmentRanges.decode(e.fragmentRanges),
                        )
                    }
                _state.update { s ->
                    s.copy(
                        status = draft.status,
                        recordedAtUtcMs = draft.recordedAtUtcMs,
                        latitude = draft.latitude,
                        longitude = draft.longitude,
                        durationMs = draft.durationMs,
                        audioPath = draft.audioPath,
                        species = rows,
                    )
                }
                val lat = draft.latitude
                val lon = draft.longitude
                if (lat != null && lon != null) {
                    val newNames = rows.map { it.taxonScientificName }.toSet()
                    val cachedNames = annotatedStatuses.keys.toSet()
                    // Skip annotation while inference is pending — launchAnnotationIfIdle()
                    // will run it on the final merged data after analysis completes.
                    // Also ignore the transient empty emission from Room's non-atomic
                    // DELETE+INSERT in attachDetections.
                    val inferenceRunning = draft.status == DraftStatus.PENDING_INFERENCE ||
                        _state.value.inferenceProgress != null ||
                        _state.value.perchProgress != null ||
                        _state.value.queuePosition != null
                    if (newNames != cachedNames && newNames.isNotEmpty() && !inferenceRunning) {
                        Log.d("ReviewVM", "annotation triggered: newNames=$newNames cachedNames=$cachedNames")
                        launchAnnotation(rows, lat, lon)
                    } else {
                        Log.d(
                            "ReviewVM",
                            "annotation skipped (inference=$inferenceRunning" +
                                " empty=${newNames.isEmpty()} same=${newNames == cachedNames})"
                        )
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
                _state.update { s ->
                    if (s.playback is PlaybackState.Playing) {
                        s.copy(playback = PlaybackState.Playing(pos))
                    } else {
                        s
                    }
                }
            }
        }
        scope.launch {
            player.isPlaying.collect { playing ->
                _state.update { s ->
                    s.copy(
                        playback = when {
                            playing -> PlaybackState.Playing(player.position.value)
                            s.playback is PlaybackState.Playing -> PlaybackState.Paused(player.position.value)
                            else -> s.playback
                        },
                    )
                }
            }
        }
        scope.launch {
            player.lastError.collect { err ->
                if (err != null) {
                    _state.update { s -> s.copy(playback = PlaybackState.Error(err)) }
                }
            }
        }
    }

    private fun startInference(path: String, lat: Double?, lon: Double?, recordedAt: Long) {
        scope.launch {
            queue.enqueue(
                QueuedJob(
                    draftId = draftId,
                    audioPath = path,
                    lat = lat,
                    lon = lon,
                    recordedAt = recordedAt,
                    runBirdnet = true,
                    runPerch = false,
                    skipYamNetGate = false,
                )
            )
        }
    }

    fun toggle(detectionId: Long, selected: Boolean) {
        scope.launch { repo.setSelection(detectionId, selected) }
    }

    fun loadObservationDetail(detectionId: Long, observationId: Long) {
        val key = observationId.toString()

        if (observationDetailCache.containsKey(key)) {
            val cached = observationDetailCache[key]
            _state.update { s ->
                s.copy(
                    species = s.species.map { row ->
                        if (row.detectionId == detectionId) {
                            row.copy(
                                observationDetailState = if (cached != null) {
                                    ObservationDetailLoadState.Loaded(cached)
                                } else {
                                    ObservationDetailLoadState.Error("Observation not available")
                                },
                            )
                        } else {
                            row
                        }
                    }
                )
            }
            return
        }

        _state.update { s ->
            s.copy(
                species = s.species.map { row ->
                    if (row.detectionId == detectionId) {
                        row.copy(
                            observationDetailState = ObservationDetailLoadState.Loading,
                        )
                    } else {
                        row
                    }
                }
            )
        }

        scope.launch {
            val detail = runCatching { observationFetcher(key) }.getOrNull()
            if (detail != null) observationDetailCache[key] = detail
            _state.update { s ->
                s.copy(
                    species = s.species.map { row ->
                        if (row.detectionId == detectionId &&
                            row.observationDetailState is ObservationDetailLoadState.Loading
                        ) {
                            row.copy(
                                observationDetailState = if (detail != null) {
                                    ObservationDetailLoadState.Loaded(detail)
                                } else {
                                    ObservationDetailLoadState.Error("Could not load observation details")
                                },
                            )
                        } else {
                            row
                        }
                    }
                )
            }
        }
    }

    fun collapseObservationDetail(detectionId: Long) {
        _state.update { s ->
            s.copy(
                species = s.species.map { row ->
                    if (row.detectionId == detectionId) {
                        row.copy(
                            observationDetailState = ObservationDetailLoadState.NotLoaded,
                        )
                    } else {
                        row
                    }
                }
            )
        }
    }

    /** Toggles whether the habitat photo strip is included for the given species row. */
    fun toggleHabitatPhoto(detectionId: Long) {
        _state.update { cur ->
            cur.copy(
                species = cur.species.map { row ->
                    if (row.detectionId == detectionId) {
                        row.copy(includeHabitatPhoto = !row.includeHabitatPhoto)
                    } else {
                        row
                    }
                },
            )
        }
    }

    /**
     * Creates a new photo file and returns a content URI suitable for
     * [ActivityResultContracts.TakePicture]. Requires [photoStore] to be set.
     */
    fun preparePhotoCapture(context: android.content.Context, draftId: String, photoId: String): android.net.Uri {
        checkNotNull(photoStore) { "Camera not available" }
        val file = photoStore.newPhotoFile(draftId, photoId)
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            FILE_PROVIDER_AUTHORITY,
            file,
        )
    }

    /**
     * Creates a new photo file and returns a content URI plus the absolute file
     * path as a [Pair]. Unlike [preparePhotoCapture] the caller does not need to
     * re-derive the path via a separate helper — everything comes from
     * [PhotoFileStore], which is the single source of truth for the storage
     * location (including the internal-storage fallback).
     */
    fun preparePhotoCaptureWithPath(
        context: android.content.Context,
        draftId: String,
        photoId: String,
    ): Pair<android.net.Uri, String> {
        checkNotNull(photoStore) { "Camera not available" }
        val file = photoStore.newPhotoFile(draftId, photoId)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            FILE_PROVIDER_AUTHORITY,
            file,
        )
        return uri to file.absolutePath
    }

    /** Persists a newly captured photo entity to the database. */
    fun onPhotoTaken(draftId: String, photoId: String, photoPath: String) {
        scope.launch(Dispatchers.IO) {
            photosDao?.insert(
                DraftPhotoEntity(
                    id = photoId,
                    draftId = draftId,
                    photoPath = photoPath,
                    takenAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** Removes a photo from the database and deletes the file from disk. */
    fun onPhotoDeleted(photoId: String, photoPath: String) {
        scope.launch(Dispatchers.IO) {
            photosDao?.deleteById(photoId)
            java.io.File(photoPath).delete()
        }
    }

    /**
     * User-triggered re-analysis. Skips the YAMNet gate (the gate adds overhead
     * without skipping model inference — see InferenceRunner). Runs the selected
     * models sequentially: BirdNET first (if [runBirdnet]), then Perch (if [runPerch]).
     * No-op when either progress field is already active.
     */
    fun reanalyze(runBirdnet: Boolean, runPerch: Boolean) {
        if (!runBirdnet && !runPerch) return
        val s = _state.value
        if (s.queuePosition != null || s.inferenceProgress != null || s.perchProgress != null) return
        val path = s.audioPath ?: return
        inferenceStarted = true
        scope.launch {
            val resolvedPath = try {
                currentProfileAudioPath()
            } catch (t: Throwable) {
                Log.w("ReviewVM", "reanalyze failed to resolve current profile audio", t)
                _state.update { it.copy(queueError = "Processed audio is not ready") }
                return@launch
            }
            queue.enqueue(
                QueuedJob(
                    draftId = draftId,
                    audioPath = resolvedPath,
                    lat = s.latitude,
                    lon = s.longitude,
                    recordedAt = s.recordedAtUtcMs,
                    runBirdnet = runBirdnet,
                    runPerch = runPerch,
                    skipYamNetGate = true,
                )
            )
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
        _state.update { s ->
            if (s.isPerchInstalled != perchInstalled) s.copy(isPerchInstalled = perchInstalled) else s
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        scope.launch {
            repo.markReviewed(draftId)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit = {}) {
        scope.launch {
            repo.delete(draftId)
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
        _state.update { it.copy(inatSubmission = InatSubmissionState.InProgress) }
        scope.launch {
            val token = tokenProvider()
            if (token.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        inatSubmission = InatSubmissionState.Failed(
                            "iNaturalist session expired — open Settings and tap Log in again",
                        ),
                    )
                }
                return@launch
            }
            val photos = _state.value.habitatPhotos.map { java.io.File(it.photoPath) }
            val includeByTaxon = _state.value.species.associate {
                it.taxonScientificName to it.includeHabitatPhoto
            }
            val spectrogramPhoto = spectrogramFile.value?.takeIf { it.exists() }
            val sourceAudioOverride = try {
                File(currentProfileAudioPath())
            } catch (t: Throwable) {
                Log.w("ReviewViewModel", "submitToINaturalist failed to resolve current profile audio", t)
                _state.update {
                    it.copy(
                        inatSubmission = InatSubmissionState.Failed("Processed audio is not ready"),
                    )
                }
                return@launch
            }
            val outcome = submission.submit(
                token = token,
                draftId = draftId,
                habitatPhotos = photos,
                includeHabitatPhotoByTaxon = includeByTaxon,
                spectrogramPhoto = spectrogramPhoto,
                sourceAudioOverride = sourceAudioOverride,
            )
            _state.update {
                it.copy(
                    inatSubmission = when (outcome) {
                        is InatSubmissionOutcome.Success -> InatSubmissionState.Done(outcome.urls)
                        is InatSubmissionOutcome.Failure -> InatSubmissionState.Failed(outcome.message)
                    },
                )
            }
        }
    }

    fun resetInatSubmission() {
        _state.update { it.copy(inatSubmission = InatSubmissionState.Idle) }
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
        cachedFilesDir = filesDir
        if (visualsStarted) return
        restartVisuals(filesDir)
    }

    private fun restartVisuals(filesDir: File) {
        val path = _state.value.audioPath ?: return
        visualsStarted = true
        visualsJob?.cancel()
        _state.update { it.copy(visualsLoading = true, visualsError = null) }
        visualsJob = scope.launch {
            try {
                val snapshot = _processingProfile.value
                val audioPath = withContext(ioDispatcher) {
                    currentProfileAudioPath(snapshot.audioProcessingConfig, path)
                }
                val v = withContext(ioDispatcher) {
                    visuals.build(audioPath, draftId, filesDir, snapshot.spectrogramConfig, snapshot.audioProcessingConfig)
                }
                _spectrogramFile.value = v.spectrogramFile
                _waveformPeaks.value = v.waveformPeaks
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Reset so a later retry (e.g. process restart, screen re-entry)
                // can run. State stays null, screen renders without visuals.
                visualsStarted = false
                _state.update { it.copy(visualsError = "Preview failed to render") }
                android.util.Log.w("ReviewViewModel", "ensureVisuals failed for draft $draftId", t)
            } finally {
                if (visualsJob === coroutineContext[Job]) {
                    _state.update { it.copy(visualsLoading = false) }
                }
            }
        }
    }

    /** Switches the spectrogram display range and re-renders. No-op if already on [range]. */
    fun setDisplayRange(range: SpectrogramDisplayRange) {
        if (_displayRange.value == range && _spectrogramConfig.value.displayRange == range) return
        updateProcessingProfile(
            _processingProfile.value.copy(
                spectrogramConfig = _processingProfile.value.spectrogramConfig.copy(displayRange = range),
            )
        )
    }

    fun setSpectrogramPalette(palette: SpectrogramPalette) {
        updateProcessingProfile(
            _processingProfile.value.copy(
                spectrogramConfig = _processingProfile.value.spectrogramConfig.copy(palette = palette)
            )
        )
    }

    fun setSpectrogramGain(gainDb: Float) {
        updateProcessingProfile(
            _processingProfile.value.copy(
                spectrogramConfig = _processingProfile.value.spectrogramConfig.copy(
                    gainDb = gainDb.coerceIn(-20f, 20f),
                )
            )
        )
    }

    fun setAudioProcessingConfig(config: ReviewAudioProcessingConfig) {
        if (config == ReviewAudioProcessingConfig.Original) {
            updateProcessingProfile(ReviewProcessingProfile.Default)
            return
        }
        updateProcessingProfile(
            _processingProfile.value.copy(audioProcessingConfig = config)
        )
    }

    fun setProcessingProfile(profile: ReviewProcessingProfile) {
        updateProcessingProfile(profile)
    }

    private fun updateProcessingProfile(profile: ReviewProcessingProfile) {
        val previousProfile = _processingProfile.value
        if (previousProfile == profile) return
        val audioChanged = previousProfile.audioProcessingConfig != profile.audioProcessingConfig
        _processingProfile.value = profile
        _spectrogramConfig.value = profile.spectrogramConfig
        _displayRange.value = profile.spectrogramConfig.displayRange
        _state.update { s ->
            s.copy(
                processingProfile = profile,
                audioProcessingConfig = profile.audioProcessingConfig,
                processedAudioPath = if (audioChanged) null else s.processedAudioPath,
                visualsError = null,
            )
        }
        val filesDir = cachedFilesDir ?: return
        if (audioChanged) {
            visualsStarted = false
            _spectrogramFile.value = null
        }
        restartVisuals(filesDir)
    }

    private suspend fun currentProfileAudioPath(
        config: ReviewAudioProcessingConfig = _processingProfile.value.audioProcessingConfig,
        originalAudioPath: String = requireNotNull(_state.value.audioPath) { "Audio file is missing" },
    ): String {
        if (!config.requiresProcessing) return originalAudioPath
        val fallbackRoot = File(originalAudioPath).parentFile
        val filesDir = cachedFilesDir ?: fallbackRoot?.let { File(it, "review_cache") }
            ?: error("Processed audio is not ready yet")
        filesDir.mkdirs()
        _state.update { it.copy(processingAudio = true) }
        return try {
            val file = withContext(ioDispatcher) {
                processedAudio.materialize(originalAudioPath, draftId, filesDir, config)
            }
            _state.update { it.copy(processedAudioPath = file.absolutePath) }
            file.absolutePath
        } finally {
            _state.update { it.copy(processingAudio = false) }
        }
    }

    fun play() {
        scope.launch {
            val path = try {
                currentProfileAudioPath()
            } catch (t: Throwable) {
                Log.w("ReviewViewModel", "play failed to resolve current profile audio", t)
                return@launch
            }
            player.start(path)
        }
    }
    fun pause() { player.pause() }
    fun seekTo(ms: Long) { player.seekTo(ms) }

    fun toggleDenoisePlayback() {
        var newValue = false
        _state.update { s ->
            newValue = !s.denoisePlayback
            s.copy(denoisePlayback = newValue)
        }
        if (newValue && denoisedPath == null) ensureDenoised()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureDenoised() {
        val path = _state.value.audioPath ?: return
        denoiseJob = scope.launch {
            try {
                val outPath = withContext(ioDispatcher) {
                    val (samples, sampleRateHz) = WavReader.readMono16(File(path))
                    val floats = FloatArray(samples.size) { samples[it] / 32768f }
                    val denoised = denoiseFull(floats, sampleRateHz)
                    val out = File(File(path).parent!!, "denoised_$draftId.wav")
                    val writer = WavWriter(out, sampleRateHz, 1, 16)
                    writer.open()
                    val shorts = ShortArray(denoised.size) {
                        (denoised[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    writer.writeShorts(shorts, 0, shorts.size)
                    writer.close()
                    out.absolutePath
                }
                denoisedPath = outPath
            } catch (t: Throwable) {
                Log.w("ReviewViewModel", "ensureDenoised failed for draft $draftId", t)
            }
        }
    }

    private fun effectivePlaybackPath(): String? {
        val s = _state.value
        return if (s.denoisePlayback && denoisedPath != null) denoisedPath else s.audioPath
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
     * Results are stored in [annotatedStatuses] (and the shared
     * [regionalStatusRepository]) then merged into [_state].species so the UI
     * updates without waiting for a Room re-emission.
     */
    private fun launchAnnotation(rows: List<SpeciesRow>, lat: Double, lon: Double) {
        val filter = regionFilter ?: run {
            Log.d("ReviewVM", "launchAnnotation: regionFilter is null, skipping")
            return
        }
        val wasRunning = annotationJob?.isActive == true
        annotationJob?.cancel()
        Log.d(
            "ReviewVM",
            "launchAnnotation: starting for ${rows.size} species (cancelled prev=$wasRunning):" +
                " ${rows.map { it.taxonScientificName }}"
        )
        annotationJob = scope.launch {
            val radiusKm = regionRadiusKmProvider()
            try {
                // Pre-flight: populate hits from the shared repo; collect misses for the network call.
                val missingRows = mutableListOf<SpeciesRow>()
                if (regionalStatusRepository != null) {
                    for (row in rows) {
                        val cached = regionalStatusRepository.getCached(row.taxonScientificName, lat, lon)
                        if (cached != null) {
                            annotatedStatuses[row.taxonScientificName] = cached
                        } else {
                            missingRows += row
                        }
                    }
                } else {
                    missingRows += rows
                }
                Log.d(
                    "ReviewVM",
                    "launchAnnotation: ${rows.size - missingRows.size} cache hits," +
                        " ${missingRows.size} misses going to network"
                )
                if (missingRows.isNotEmpty()) {
                    val annotated = filter.annotate(
                        missingRows.map { r ->
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
                        annotatedStatuses[det.taxonScientificName] = det.regionalStatus
                        det.regionalStatus?.let { s ->
                            regionalStatusRepository?.storeResult(det.taxonScientificName, lat, lon, s)
                        }
                    }
                }
                Log.d("ReviewVM", "launchAnnotation: complete, updating state with ${annotatedStatuses.size} statuses")
                _state.update { s ->
                    s.copy(
                        species = s.species.map { row ->
                            row.copy(regionalStatus = annotatedStatuses[row.taxonScientificName])
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(
                    "ReviewVM",
                    "launchAnnotation: job cancelled after annotating ${annotatedStatuses.size} species"
                )
                throw e
            }
        }
    }

    fun onShareFullRecording() {
        _state.update { it.copy(exportingAction = ExportingAction.FullRecordingShare) }
        scope.launch(ioDispatcher) {
            try {
                val snapshot = _state.value
                val path = snapshot.audioPath
                val file = if (path != null) File(path) else null
                require(file != null && file.exists() && file.isFile && file.length() > 0L)
                emitEffect(ReviewExportEffect.ShareAudioFile(file, buildFullRecordingShareText(snapshot)))
            } catch (_: IllegalArgumentException) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio file is missing"))
            } catch (_: Exception) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Could not share audio"))
            } finally {
                clearExportingAction()
            }
        }
    }

    fun onSaveFullRecording() {
        _state.update { it.copy(exportingAction = ExportingAction.FullRecordingSave) }
        scope.launch(ioDispatcher) {
            try {
                val path = _state.value.audioPath
                val file = if (path != null) File(path) else null
                require(file != null && file.exists() && file.isFile && file.length() > 0L)
                audioSaver.saveToDownloads(file, buildDisplayName("recording"))
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio saved to Downloads"))
            } catch (_: UnsupportedOperationException) {
                emitEffect(
                    ReviewExportEffect.ShowSnackbar(
                        "Saving to Downloads is not supported on this Android version"
                    )
                )
            } catch (_: IllegalArgumentException) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio file is missing"))
            } catch (_: Exception) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Could not save audio"))
            } finally {
                clearExportingAction()
            }
        }
    }

    fun onShareSpeciesClip(row: SpeciesRow) {
        _state.update { it.copy(exportingAction = ExportingAction.SpeciesClipShare(row.detectionId)) }
        scope.launch(ioDispatcher) {
            try {
                val snapshot = _state.value
                val clip = prepareSpeciesClip(row)
                emitEffect(ReviewExportEffect.ShareAudioFile(clip, buildClipShareText(snapshot, row)))
            } catch (_: IllegalArgumentException) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio file is missing"))
            } catch (_: Exception) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Could not share audio"))
            } finally {
                clearExportingAction()
            }
        }
    }

    fun onSaveSpeciesClip(row: SpeciesRow) {
        _state.update { it.copy(exportingAction = ExportingAction.SpeciesClipSave(row.detectionId)) }
        scope.launch(ioDispatcher) {
            try {
                val clip = prepareSpeciesClip(row)
                val safe = row.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
                audioSaver.saveToDownloads(clip, buildDisplayName("clip_$safe"))
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio saved to Downloads"))
            } catch (_: UnsupportedOperationException) {
                emitEffect(
                    ReviewExportEffect.ShowSnackbar(
                        "Saving to Downloads is not supported on this Android version"
                    )
                )
            } catch (_: IllegalArgumentException) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Audio file is missing"))
            } catch (_: Exception) {
                emitEffect(ReviewExportEffect.ShowSnackbar("Could not save audio"))
            } finally {
                clearExportingAction()
            }
        }
    }

    fun consumeExportEffect() {
        _state.update { it.copy(exportEffect = null) }
    }

    private fun prepareSpeciesClip(row: SpeciesRow): File {
        val snapshot = _state.value
        val srcPath = requireNotNull(snapshot.audioPath) { "No audio path available" }
        val srcFile = File(srcPath)
        require(srcFile.exists() && srcFile.isFile && srcFile.length() > 0L) {
            "Source audio missing or empty"
        }
        val durationMs = snapshot.durationMs
        require(durationMs > 0L) { "Recording duration not yet loaded" }
        val startMs = maxOf(0L, row.firstSeenMs - CLIP_PADDING_MS)
        val endMs = minOf(durationMs, row.lastSeenMs + CLIP_PADDING_MS)
        require(endMs > startMs) { "Clip range is empty: $startMs..$endMs" }
        val safe = row.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
        val clipFile = File(exportClipsDir, "${draftId}__${safe}__${row.firstSeenMs}_${row.lastSeenMs}.wav")
        if (clipFile.exists() && clipFile.length() > 0L) return clipFile
        exportClipsDir.mkdirs()
        val tmp = File(exportClipsDir, "${clipFile.name}.tmp")
        try {
            WavTrimmer.trimMono16(srcPath, tmp, startMs, endMs)
            if (!tmp.renameTo(clipFile)) {
                // renameTo can fail across filesystems; fall back to copy+delete
                tmp.copyTo(clipFile, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return clipFile
    }

    private fun emitEffect(effect: ReviewExportEffect) {
        _state.update { it.copy(exportEffect = effect) }
    }

    private fun clearExportingAction() {
        _state.update { it.copy(exportingAction = null) }
    }

    private fun buildDisplayName(prefix: String): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return "${prefix}_${sdf.format(java.util.Date(_state.value.recordedAtUtcMs))}.wav"
    }

    private fun buildFullRecordingShareText(snapshot: ReviewUiState): String {
        val sb = StringBuilder("WildEar recording — ")
        sb.append(formatShareDateTime(snapshot.recordedAtUtcMs))
        formatShareCoords(snapshot.latitude, snapshot.longitude)?.let {
            sb.append("\nLocation: ").append(it)
        }
        val sorted = snapshot.species.sortedByDescending { it.maxConfidence }
        if (sorted.isNotEmpty()) {
            sb.append("\n\nDetected:")
            sorted.forEach { row -> sb.append("\n• ").append(formatSpeciesLine(row)) }
        }
        return sb.toString()
    }

    private fun buildClipShareText(snapshot: ReviewUiState, row: SpeciesRow): String {
        val sb = StringBuilder("WildEar — ")
        sb.append(formatSpeciesLine(row))
        sb.append("\n").append(formatShareDateTime(snapshot.recordedAtUtcMs))
        formatShareCoords(snapshot.latitude, snapshot.longitude)?.let {
            sb.append("\nLocation: ").append(it)
        }
        return sb.toString()
    }

    private fun formatSpeciesLine(row: SpeciesRow): String {
        val name = if (row.taxonCommonName != null) {
            "${row.taxonCommonName} (${row.taxonScientificName})"
        } else {
            row.taxonScientificName
        }
        return "$name — ${"%.0f".format(row.maxConfidence * 100)}%"
    }

    private fun formatShareDateTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return sdf.format(java.util.Date(epochMs))
    }

    private fun formatShareCoords(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        val latStr = "${"%.4f".format(Math.abs(lat))}°${if (lat >= 0) "N" else "S"}"
        val lonStr = "${"%.4f".format(Math.abs(lon))}°${if (lon >= 0) "E" else "W"}"
        return "$latStr, $lonStr"
    }

    private companion object {
        /** Milliseconds an overlay/row stays highlighted after a tap. */
        const val HighlightDurationMs = 800L

        private const val CLIP_PADDING_MS = 1_000L
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
    private val settings: Settings,
    private val submitter: INatSubmitter,
    private val inatObservationsDao: InatObservationDao,
    private val inatClient: INaturalistClient,
    private val regionFilter: RegionFilter,
    private val regionalStatusRepository: RegionalStatusRepository,
    private val inferenceUseCase: InferenceUseCase,
    private val inatAuth: com.sound2inat.inat.INatAuthRepository,
    private val modelManager: ModelManager,
    private val photosDao: DraftPhotoDao,
    private val photoStore: PhotoFileStore,
    private val queue: InferenceQueue,
    private val audioExport: AudioExportManager,
) {
    /** Cache root used by the screen's `ensureVisuals` call. */
    val filesDir: File get() = context.filesDir

    fun create(draftId: String, externalScope: CoroutineScope): ReviewViewModel = ReviewViewModel(
        draftId = draftId,
        repo = repo,
        player = MediaPlayerAudioPlayer(),
        inference = inferenceUseCase.inference,
        visuals = ProductionVisualsProvider(),
        processedAudio = ProductionProcessedAudioProvider(),
        submission = InatSubmissionJob { token, id, photoFiles, includeByTaxon, spectrogramPhoto, sourceAudioOverride ->
            // Pulling the freshest draft + detections so the submitter sees the
            // user's current selection, not a stale snapshot.
            val dwd = repo.observeWithDetections(id).first()
            when (val r = submitter.submit(
                token = token,
                draft = dwd,
                habitatPhotos = photoFiles,
                includeHabitatPhotoByTaxon = includeByTaxon,
                spectrogramPhoto = spectrogramPhoto,
                sourceAudioOverride = sourceAudioOverride,
            )) {
                is INatSubmitter.Result.Ok -> InatSubmissionOutcome.Success(r.urls)
                is INatSubmitter.Result.Failure -> InatSubmissionOutcome.Failure(r.message)
            }
        },
        tokenProvider = { inatAuth.getValidToken() },
        inatObservationsFlow = inatObservationsDao.observeForDraft(draftId)
            .map { rows -> rows.map { InatObsEntry(it.taxonScientificName, it.observationId, it.observationUrl) } },
        photoFetcher = { name -> inatClient.fetchTaxonPhotoUrl(name) },
        perchAnalysis = inferenceUseCase.perchAnalysis,
        inferenceReanalysis = inferenceUseCase.inferenceReanalysis,
        perchReanalysis = inferenceUseCase.perchReanalysis,
        perchInstalledProbe = {
            modelManager.stateFor(com.sound2inat.modelmanager.PerchV2.descriptor) is
            ModelInstallState.Ready
        },
        regionFilter = regionFilter,
        minWindowsProvider = { settings.minWindows.first() },
        regionRadiusKmProvider = { settings.regionRadiusKm.first() },
        observationFetcher = { id -> inatClient.getObservation(id) },
        regionalStatusRepository = regionalStatusRepository,
        habitatPhotosFlow = photosDao.photosForDraft(draftId),
        photosDao = photosDao,
        photoStore = photoStore,
        audioSaver = AudioSaver { file, name -> audioExport.saveToDownloads(file, name) },
        exportClipsDir = File(context.cacheDir, "export_clips"),
        queue = queue,
        externalScope = externalScope,
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

/** Default sink used in tests; never builds anything. */
internal object NoopVisualsProvider : VisualsProvider {
    override suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
        audioProcessingConfig: ReviewAudioProcessingConfig,
    ): Visuals = error("NoopVisualsProvider should not be invoked; supply a real VisualsProvider")
}

/**
 * Production [VisualsProvider]. Streams the WAV off disk so the preview can be
 * built without loading the full file into memory, then caches a PNG under
 * `<filesDir>/spectrograms/<draftId>.png` and computes waveform peaks from the
 * same stream.
 *
 * Cache policy: if the PNG already exists, the spectrogram step is skipped
 * (we still re-read the WAV for waveform peaks, which are not cached). This
 * keeps the second open instant for the spectrogram while the cheap peak
 * computation runs again on each open.
 */
internal class ProductionVisualsProvider(
    private val matrixCache: ReviewSpectrogramMatrixCache = ReviewSpectrogramMatrixCache(),
    private val pngWriter: SpectrogramPngWriter = SpectrogramPngWriter(SpectrogramBitmap::writePng),
) : VisualsProvider {

    override suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
        audioProcessingConfig: ReviewAudioProcessingConfig,
    ): Visuals {
        val input = File(audioPath)
        val wavInfo = readMono16Info(input)
        val pngDir = File(filesDir, "spectrograms").apply { mkdirs() }
        val rendererConfig = if (config.displayRange == SpectrogramDisplayRange.FULL) {
            config.copy(
                palette = SpectrogramPalette.VIRIDIS,
                noiseFloorMode = SpectrogramNoiseFloorMode.NONE,
                gateDb = 0f,
                displayRangeDb = 80f,
                gamma = 1f,
                smoothingTimeRadius = 0,
                smoothingFrequencyRadius = 0,
            )
        } else {
            config
        }
        val analysisConfig = ReviewSpectrogramAnalysisConfig.from(
            displayRange = rendererConfig.displayRange,
            sampleRateHz = wavInfo.sampleRateHz,
        )
        // Cache key includes the visual profile plus the audio profile so
        // processed and original sources never collide on disk.
        val pngFile = File(
            pngDir,
            "${draftId}_${rendererConfig.cacheSuffix()}_${audioProcessingConfig.cacheSuffix()}.png",
        )
        if (!pngFile.exists()) {
            val matrix = matrixCache.getOrCreate(
                audioFile = input,
                draftId = draftId,
                filesDir = filesDir,
                config = analysisConfig,
                readSamples = { file ->
                    val (samples, _) = WavReader.readMono16(file)
                    FloatArray(samples.size) { index -> samples[index] / Short.MAX_VALUE.toFloat() }
                },
            )
            val pixels = SpectrogramRenderer(
                targetWidth = SpectrogramRenderer.DEFAULT_TARGET_WIDTH,
                config = rendererConfig,
            ).render(matrix)
            if (pixels.isNotEmpty()) {
                pngWriter.write(pixels, pngFile)
            }
        }
        val peaks = buildWaveformPeaks(input, wavInfo)
        return Visuals(spectrogramFile = pngFile, waveformPeaks = peaks)
    }
}

internal data class Mono16Info(
    val sampleRateHz: Int,
    val totalSamples: Long,
)

internal fun readMono16Info(file: File): Mono16Info {
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(WAV_HEADER_SIZE).also { raf.readFully(it) }
        require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
            "Not a WAV file"
        }
        val channels = leU16(header, 22)
        val sampleRateHz = leU32(header, 24).toInt()
        val bitsPerSample = leU16(header, 34)
        require(channels == 1 && bitsPerSample == WAV_BITS_PER_SAMPLE) {
            "Mono 16-bit PCM only (got ch=$channels bits=$bitsPerSample)"
        }
        require(String(header, 36, 4) == "data") {
            "WAV 'data' chunk not at offset 36 — unsupported chunk layout"
        }
        val dataSize = leU32(header, 40)
        require(dataSize in 0L..Long.MAX_VALUE / WAV_BYTES_PER_SAMPLE) {
            "WAV dataSize out of safe range: $dataSize bytes"
        }
        return Mono16Info(sampleRateHz = sampleRateHz, totalSamples = dataSize / WAV_BYTES_PER_SAMPLE)
    }
}

internal fun buildWaveformPeaks(file: File, info: Mono16Info): FloatArray {
    if (info.totalSamples <= 0L) return FloatArray(0)
    val width = minOf(WaveformBitmap.DEFAULT_TARGET_WIDTH, info.totalSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    if (width <= 0) return FloatArray(0)
    val lows = FloatArray(width) { Float.POSITIVE_INFINITY }
    val highs = FloatArray(width) { Float.NEGATIVE_INFINITY }
    streamMono16(file) { chunk, startSample ->
        for (i in chunk.indices) {
            val sampleIndex = startSample + i
            val bucket = ((sampleIndex * width) / info.totalSamples).toInt().coerceIn(0, width - 1)
            val value = chunk[i] / Short.MAX_VALUE.toFloat()
            if (value < lows[bucket]) lows[bucket] = value
            if (value > highs[bucket]) highs[bucket] = value
        }
    }
    return FloatArray(width * 2) { idx ->
        val bucket = idx / 2
        if (idx % 2 == 0) lows[bucket].takeIf { it.isFinite() } ?: 0f
        else highs[bucket].takeIf { it.isFinite() } ?: 0f
    }
}

private fun streamMono16(
    file: File,
    blockSamples: Int = WAV_READ_BLOCK_SAMPLES,
    onChunk: (chunk: ShortArray, startSample: Long) -> Unit,
) {
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(WAV_HEADER_SIZE).also { raf.readFully(it) }
        require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
            "Not a WAV file"
        }
        val channels = leU16(header, 22)
        val sampleRateHz = leU32(header, 24).toInt()
        val bitsPerSample = leU16(header, 34)
        require(channels == 1 && bitsPerSample == WAV_BITS_PER_SAMPLE) {
            "Mono 16-bit PCM only (got ch=$channels bits=$bitsPerSample)"
        }
        require(String(header, 36, 4) == "data") {
            "WAV 'data' chunk not at offset 36 — unsupported chunk layout"
        }
        val dataSize = leU32(header, 40)
        val totalSamples = dataSize / WAV_BYTES_PER_SAMPLE
        val raw = ByteArray(blockSamples * WAV_BYTES_PER_SAMPLE)
        var startSample = 0L
        while (startSample < totalSamples) {
            val samplesToRead = minOf(blockSamples.toLong(), totalSamples - startSample).toInt()
            raf.readFully(raw, 0, samplesToRead * WAV_BYTES_PER_SAMPLE)
            val chunk = ShortArray(samplesToRead)
            for (i in 0 until samplesToRead) {
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                chunk[i] = ((hi shl 8) or lo).toShort()
            }
            onChunk(chunk, startSample)
            startSample += samplesToRead
        }
    }
}

private fun leU16(buf: ByteArray, o: Int): Int =
    (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

private fun leU32(buf: ByteArray, o: Int): Long =
    (buf[o].toLong() and 0xFF) or
        ((buf[o + 1].toLong() and 0xFF) shl 8) or
        ((buf[o + 2].toLong() and 0xFF) shl 16) or
        ((buf[o + 3].toLong() and 0xFF) shl 24)

private const val WAV_HEADER_SIZE = 44
private const val WAV_BYTES_PER_SAMPLE = 2
private const val WAV_BITS_PER_SAMPLE = 16
private const val WAV_READ_BLOCK_SAMPLES = 16_384

internal class ProductionProcessedAudioProvider : ProcessedAudioProvider {
    override suspend fun materialize(
        originalAudioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewAudioProcessingConfig,
    ): File {
        val input = File(originalAudioPath)
        if (!config.requiresProcessing) return input
        val outDir = File(filesDir, "processed_audio").apply { mkdirs() }
        val outFile = File(outDir, "${draftId}_${config.cacheSuffix()}.wav")
        if (outFile.exists() && outFile.length() > 0L) return outFile
        val (samples, sampleRateHz) = WavReader.readMono16(input)
        val processed = ReviewAudioProcessor.process(samples, sampleRateHz, config)
        val writer = WavWriter(outFile, sampleRateHz, channels = 1, bitsPerSample = 16)
        writer.open()
        try {
            writer.writeShorts(processed, 0, processed.size)
        } finally {
            writer.close()
        }
        return outFile
    }
}
