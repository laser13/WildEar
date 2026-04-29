package com.sound2inat.app.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.InferenceRunner
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import dagger.hilt.android.lifecycle.HiltViewModel
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
    ) : InferenceOutcome

    data class Failure(val message: String) : InferenceOutcome
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(ReviewUiState(draftId = draftId))
    val state: StateFlow<ReviewUiState> = _state

    private var inferenceStarted = false
    private var inferenceJob: Job? = null

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

    fun save() {
        scope.launch(ioDispatcher) { repo.markReviewed(draftId) }
    }

    fun delete() {
        scope.launch(ioDispatcher) { repo.delete(draftId) }
    }

    fun play() { _state.value.audioPath?.let { player.start(it) } }
    fun pause() { player.pause() }
    fun seekTo(ms: Long) { player.seekTo(ms) }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}

@HiltViewModel
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: DraftRepository,
    private val model: BioacousticModel,
    private val modelManager: ModelManager,
    private val settings: Settings,
) : ViewModel() {

    private val draftId: String = checkNotNull(savedStateHandle.get<String>("draftId")) {
        "Review screen requires draftId nav arg"
    }

    private val player: AudioPlayer = MediaPlayerAudioPlayer()

    val delegate = ReviewViewModel(
        draftId = draftId,
        repo = repo,
        player = player,
        inference = ProductionInferenceJob(model, modelManager, settings),
    )
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
            )
        } catch (t: Throwable) {
            InferenceOutcome.Failure(t.message ?: t::class.simpleName.orEmpty())
        }
    }
}
