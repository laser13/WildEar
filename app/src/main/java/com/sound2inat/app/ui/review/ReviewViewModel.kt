package com.sound2inat.app.ui.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatSubmitter
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.BioacousticModel
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
        /** Species that passed the absolute floor but were filtered by current settings.
         *  Shown grayed-out in the UI; not saved to DB. */
        val candidateDetections: List<AggregatedDetection> = emptyList(),
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
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(ReviewUiState(draftId = draftId))
    val state: StateFlow<ReviewUiState> = _state

    private val _spectrogramFile = MutableStateFlow<File?>(null)
    val spectrogramFile: StateFlow<File?> = _spectrogramFile

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

    /** Latest playback position from the player; ticks every 50 ms during playback. */
    val playerPosition: StateFlow<Long> = player.position

    private var inferenceStarted = false
    private var inferenceJob: Job? = null
    private var visualsStarted = false
    private var visualsJob: Job? = null

    init {
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
                _state.value = _state.value.copy(
                    status = draft.status,
                    recordedAtUtcMs = draft.recordedAtUtcMs,
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    durationMs = draft.durationMs,
                    audioPath = draft.audioPath,
                    species = dwd.detections.map { e ->
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
                        )
                    },
                )
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
                    withContext(ioDispatcher) {
                        repo.attachDetections(
                            draftId = draftId,
                            modelId = outcome.modelId,
                            modelVersion = outcome.modelVersion,
                            items = outcome.detections,
                        )
                        // Auto-promote PENDING_REVIEW → REVIEWED so the Submit
                        // button is enabled by the user's checkbox selection
                        // alone — no separate Save tap. Skipped when nothing
                        // was detected (REVIEWED on empty draft is misleading).
                        if (outcome.detections.isNotEmpty()) {
                            repo.markReviewed(draftId)
                        }
                    }
                    _windowPreds.value = outcome.windows
                    val candidateRows = outcome.candidateDetections.mapIndexed { i, det ->
                        SpeciesRow(
                            detectionId = -(i + 1L),
                            taxonScientificName = det.taxonScientificName,
                            taxonCommonName = det.taxonCommonName,
                            maxConfidence = det.maxConfidence,
                            detectedWindows = det.detectedWindows,
                            firstSeenMs = det.firstSeenMs,
                            lastSeenMs = det.lastSeenMs,
                            isSelected = false,
                            confidenceBySource = det.confidenceBySource,
                        )
                    }
                    _state.value = _state.value.copy(
                        inferenceProgress = null,
                        candidates = candidateRows,
                    )
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
                    inatSubmission = InatSubmissionState.Failed("Set iNaturalist token in Settings first"),
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

    fun play() { _state.value.audioPath?.let { player.start(it) } }
    fun pause() { player.pause() }
    fun seekTo(ms: Long) { player.seekTo(ms) }

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
     * Release the underlying [AudioPlayer]. Must be called by whichever
     * scope owns the VM — Hilt's [ViewModel.onCleared] does NOT cascade to
     * this delegate (the wrapper [ReviewViewModelHilt] is the one tied to
     * the [androidx.lifecycle.ViewModelStore]).
     */
    fun release() {
        player.release()
    }

    private companion object {
        /** Milliseconds an overlay/row stays highlighted after a tap. */
        const val HighlightDurationMs = 800L
    }
}

@HiltViewModel
@Suppress("LongParameterList")
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
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
) : ViewModel() {

    private val draftId: String = checkNotNull(savedStateHandle.get<String>("draftId")) {
        "Review screen requires draftId nav arg"
    }

    /** Cache root used by the screen's `ensureVisuals` call. */
    val filesDir: File = context.filesDir

    private val player: AudioPlayer = MediaPlayerAudioPlayer()

    val delegate = ReviewViewModel(
        draftId = draftId,
        repo = repo,
        player = player,
        inference = ProductionInferenceJob(
            models,
            descriptors,
            modelManager,
            settings,
            regionFilter,
            yamNetGate,
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
        tokenProvider = { settings.inatToken.first() },
        inatObservationsFlow = inatObservationsDao.observeForDraft(draftId)
            .map { rows -> rows.map { it.taxonScientificName to it.observationUrl } },
        photoFetcher = { name -> inatClient.fetchTaxonPhotoUrl(name) },
    )

    override fun onCleared() {
        super.onCleared()
        delegate.release()
    }
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
                allPreds += perModel
                succeeded += model
                android.util.Log.i(TAG, "${model.modelId} produced ${perModel.size} window predictions")
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

        if (latitude != null && longitude != null) {
            settings.setLastKnownCoords(latitude, longitude)
        }

        val filteredDetections = when {
            !settings.regionalFilterEnabled.first() -> rawDetections
            latitude != null && longitude != null -> {
                val radius = settings.regionRadiusKm.first()
                regionFilter.filter(rawDetections, latitude, longitude, radius)
            }
            else -> {
                val lastLat = settings.lastKnownLat.first()
                val lastLon = settings.lastKnownLon.first()
                if (lastLat != null && lastLon != null) {
                    val radius = settings.regionRadiusKm.first()
                    regionFilter.filter(rawDetections, lastLat, lastLon, radius)
                } else {
                    rawDetections
                }
            }
        }

        val acceptedNames = filteredDetections.map { it.taxonScientificName }.toSet()
        val floorAggregator = DetectionAggregator(minConfidence = CANDIDATE_MIN_CONFIDENCE, minWindows = 1)
        val candidateDetections = floorAggregator.aggregate(allPreds)
            .filter { it.taxonScientificName !in acceptedNames }

        InferenceOutcome.Success(
            modelId = ids,
            modelVersion = versions,
            detections = filteredDetections,
            windows = allPreds,
            candidateDetections = candidateDetections,
        )
    }

    private companion object {
        const val TAG = "ProductionInferenceJob"
        const val CANDIDATE_MIN_CONFIDENCE = 0.01f
    }
}
