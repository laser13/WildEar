package com.sound2inat.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@Suppress("LongParameterList", "TooManyFunctions")
class RecordingViewModel(
    private val perms: PermissionsController,
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val engineFactory: LiveInferenceEngineFactory? = null,
    private val minConfidence: Flow<Float> = flowOf(DEFAULT_MIN_CONFIDENCE),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val softLimitMs: Long = SOFT_LIMIT_MS,
    private val hardLimitMs: Long = HARD_LIMIT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val state: StateFlow<RecordingUiState> = _state

    /** Forwarded so the live waveform composable can collect it directly. */
    val rmsHistory: StateFlow<FloatArray> = recorder.rmsHistory

    /** Forwarded for the live spectrogram view. */
    val audioBlocks: SharedFlow<FloatArray> = recorder.audioBlocks

    /** Forwarded for the live spectrogram view. */
    val sampleRateHz: Int get() = recorder.sampleRate

    private var draftId: String? = null
    private var recordingStartMs: Long = 0L
    private var fix: Fix? = null
    private var tickJob: Job? = null
    private var rmsJob: Job? = null
    private var locationJob: Job? = null
    private var feedJob: Job? = null
    private var predictionsJob: Job? = null
    private var backlogJob: Job? = null

    private var activeEngine: LiveInferenceEngine? = null
    private var activeAggregator: DetectionAggregator? = null

    fun start() {
        viewModelScope.launch {
            val granted = perms.request(setOf(Permission.RECORD_AUDIO, Permission.ACCESS_FINE_LOCATION))
            if (granted[Permission.RECORD_AUDIO] != PermissionStatus.GRANTED) {
                _state.value = RecordingUiState.Error("Microphone permission required.")
                return@launch
            }
            val id = UUID.randomUUID().toString().also { draftId = it }
            val target = files.newRecordingFile(id)
            recordingStartMs = nowMs()
            recorder.start(target)
            _state.value = RecordingUiState.Recording(0L, 0f, GpsStatus.Acquiring, false)

            locationJob = viewModelScope.launch { fix = location.getCurrent(locationTimeoutMs) }
            tickJob = viewModelScope.launch { tickLoop() }
            rmsJob = viewModelScope.launch {
                recorder.rmsLevel.collect { rms ->
                    val cur = _state.value as? RecordingUiState.Recording ?: return@collect
                    _state.value = cur.copy(rms = rms)
                }
            }
            startLiveInference()
        }
    }

    private suspend fun startLiveInference() {
        val factory = engineFactory ?: return
        val threshold = runCatching { minConfidence.first() }.getOrDefault(DEFAULT_MIN_CONFIDENCE)
        val aggregator = DetectionAggregator(minConfidence = threshold)
        // Factory returns null when BirdNET isn't installed or failed to load —
        // VM stays without a live engine and Stop will fall back to PENDING_INFERENCE.
        val engine = factory.create(recorder.sampleRate) ?: return
        activeEngine = engine
        activeAggregator = aggregator
        engine.start(viewModelScope)
        feedJob = viewModelScope.launch {
            recorder.audioBlocks.collect { engine.feed(it) }
        }
        predictionsJob = viewModelScope.launch {
            engine.predictions.collect { pred ->
                val snap = aggregator.addWindow(pred)
                val cur = _state.value as? RecordingUiState.Recording ?: return@collect
                _state.value = cur.copy(liveCards = snap.map { it.toLiveCard() })
            }
        }
        backlogJob = viewModelScope.launch {
            engine.backlog.collect { depth ->
                val cur = _state.value as? RecordingUiState.Recording ?: return@collect
                _state.value = cur.copy(backlogWindows = depth)
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(tickIntervalMs)
            val cur = _state.value as? RecordingUiState.Recording ?: return
            val elapsed = nowMs() - recordingStartMs
            val gps = fix?.let { GpsStatus.Fix(it.latitude, it.longitude, it.accuracyMeters) }
                ?: if (elapsed >= locationTimeoutMs) GpsStatus.NoFix else GpsStatus.Acquiring
            val soft = elapsed >= softLimitMs
            if (elapsed >= hardLimitMs) {
                stopInternal()
                return
            }
            _state.value = cur.copy(elapsedMs = elapsed, gps = gps, warningSoftLimit = soft)
        }
    }

    fun stop() {
        viewModelScope.launch { stopInternal() }
    }

    private suspend fun stopInternal() {
        val id = draftId ?: return
        // Drain pending live windows BEFORE stopping the recorder so the worker
        // gets to flush any in-flight predictions into the aggregator.
        val engine = activeEngine
        val finalDetections: List<AggregatedDetection> = if (engine != null) {
            engine.stop()
            activeAggregator?.snapshot().orEmpty()
        } else {
            emptyList()
        }
        val result = recorder.stop()
        cancelJobs()
        withContext(ioDispatcher) {
            if (engine != null && finalDetections.isNotEmpty()) {
                drafts.createWithDetections(
                    id = id,
                    audioPath = result.audioPath,
                    recordedAtUtcMs = recordingStartMs,
                    durationMs = result.durationMs,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                    modelId = LIVE_MODEL_ID,
                    modelVersion = LIVE_MODEL_VERSION,
                    detections = finalDetections,
                )
            } else {
                drafts.create(
                    id = id,
                    audioPath = result.audioPath,
                    recordedAtUtcMs = recordingStartMs,
                    durationMs = result.durationMs,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                )
            }
        }
        activeEngine = null
        activeAggregator = null
        _state.value = RecordingUiState.Done(id)
    }

    fun cancel() {
        recorder.cancel()
        cancelJobs()
        // Best-effort engine shutdown — fire-and-forget on the VM scope so
        // cancel() stays non-suspend for callers (UI back button).
        val engine = activeEngine
        if (engine != null) {
            viewModelScope.launch { engine.stop() }
            activeEngine = null
            activeAggregator = null
        }
        _state.value = RecordingUiState.Idle
    }

    private fun cancelJobs() {
        tickJob?.cancel(); tickJob = null
        rmsJob?.cancel(); rmsJob = null
        locationJob?.cancel(); locationJob = null
        feedJob?.cancel(); feedJob = null
        predictionsJob?.cancel(); predictionsJob = null
        backlogJob?.cancel(); backlogJob = null
    }

    private fun AggregatedDetection.toLiveCard() = LiveCard(
        scientificName = taxonScientificName,
        commonName = taxonCommonName,
        count = detectedWindows,
        peakConfidence = maxConfidence,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
    )

    companion object {
        const val SOFT_LIMIT_MS = 5L * 60_000L
        const val HARD_LIMIT_MS = 10L * 60_000L
        const val TICK_INTERVAL_MS = 100L
        const val LOCATION_TIMEOUT_MS = 15_000L
        const val DEFAULT_MIN_CONFIDENCE = 0.25f
        const val LIVE_MODEL_ID = "birdnet_v2_4"
        const val LIVE_MODEL_VERSION = "2.4"
    }
}

@HiltViewModel
class RecordingViewModelHilt @Inject constructor(
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val engineFactory: LiveInferenceEngineFactory?,
    private val settings: Settings,
) : ViewModel() {
    // PermissionsController depends on the host ComponentActivity, so it's not Hilt-singleton.
    // The screen creates the delegate after collecting LocalPermissionsController.
    val factory = { perms: PermissionsController ->
        RecordingViewModel(
            perms = perms,
            recorder = recorder,
            location = location,
            files = files,
            drafts = drafts,
            engineFactory = engineFactory,
            minConfidence = settings.minConfidenceDisplay,
        )
    }
}
