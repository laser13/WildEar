package com.sound2inat.app.ui.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.InferenceRunner
import com.sound2inat.inference.WavReader
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

@Suppress("LongParameterList")
class ReviewViewModel(
    private val draftId: String,
    private val repo: DraftRepository,
    private val player: AudioPlayer,
    private val inference: InferenceJob,
    private val visuals: VisualsProvider = NoopVisualsProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
                    }
                    _windowPreds.value = outcome.windows
                    _state.value = _state.value.copy(inferenceProgress = null)
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
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repo: DraftRepository,
    private val model: BioacousticModel,
    private val modelManager: ModelManager,
    private val settings: Settings,
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
        inference = ProductionInferenceJob(model, modelManager, settings),
        visuals = ProductionVisualsProvider(),
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
 * Wires the production stack: [ModelManager.stateFor] (off Main),
 * [BioacousticModel.load], [InferenceRunner], [DetectionAggregator]
 * (with min-confidence pulled from [Settings]). The progress-collector
 * coroutine is bounded by [coroutineScope] so it cannot leak past `run()`.
 */
private class ProductionInferenceJob(
    private val model: BioacousticModel,
    private val modelManager: ModelManager,
    private val settings: Settings,
) : InferenceJob {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): InferenceOutcome = withContext(Dispatchers.IO) {
        try {
            val ready = modelManager.stateFor(BirdNetV24.descriptor) as? ModelInstallState.Ready
                ?: return@withContext InferenceOutcome.Failure("Model not installed")
            model.load(ready.modelFile, ready.labelsFile)
            val runner = InferenceRunner(model)
            val minConf = settings.minConfidenceDisplay.first()
            val aggregator = DetectionAggregator(minConfidence = minConf)
            val preds = coroutineScope {
                val collector = launch {
                    runner.progress.collect { onProgress(it) }
                }
                val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                collector.cancel()
                result
            }
            InferenceOutcome.Success(
                modelId = model.modelId,
                modelVersion = model.modelVersion,
                detections = aggregator.aggregate(preds),
                windows = preds,
            )
        } catch (t: Throwable) {
            InferenceOutcome.Failure(t.message ?: t::class.simpleName.orEmpty())
        }
    }
}
